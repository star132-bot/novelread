"""MKread sign-in through MKauth: device sessions for the app, cookie sessions for the admin console.

App flow (app ↔ this server ↔ MKauth):

1. The app opens  GET /api/v1/auth/start?port=P&state=S&code_challenge=C  in a browser tab.
   P is a loopback port the app listens on; C is the app's PKCE S256 challenge.
2. This server redirects to MKauth (authorization code + its own PKCE). It is a confidential
   client, so the client secret never leaves the server.
3. MKauth redirects back to /api/v1/auth/callback. The server redeems the code, reads the
   user's profile, and redirects the browser to http://127.0.0.1:P/callback with a one-time
   code bound to C.
4. The app posts that code with its PKCE verifier to /api/v1/auth/token and receives a
   long-lived device token used for catalog sync and downloads.

Admin console flow: GET /api/v1/auth/admin/start goes through the same MKauth callback (the
pending request remembers its purpose) and ends with an 8-hour HttpOnly session cookie.
Requests that change data must also carry the header ``X-MKread-Admin: 1``; a cross-site page
cannot add custom headers without CORS, which this server does not allow.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from urllib.parse import unquote, urlencode

import httpx
from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import RedirectResponse, Response
from pydantic import BaseModel

from . import accounts
from .accounts import ROLE_NAMES, effective_role, role_at_least
from .config import Settings
from .db import Database


@dataclass(frozen=True)
class Principal:
    subject: str
    name: str | None
    role: str
    # "device" (app token), "console" (admin cookie), "token" (ADMIN_API_TOKEN),
    # "service" (a trusted platform such as Server Hub acting for its user) or "anonymous".
    via: str = "device"

    @property
    def is_admin(self) -> bool:
        """May publish books (the app shows admin features)."""
        return role_at_least(self.role, "operator")


ANONYMOUS = Principal(subject="anonymous", name=None, role="none", via="anonymous")
AUTH_REQUEST_TTL = 600
APP_CODE_TTL = 120
ADMIN_COOKIE = "mkread_admin"
ADMIN_SESSION_TTL = timedelta(hours=8)
ADMIN_HEADER = "x-mkread-admin"
SAFE_METHODS = {"GET", "HEAD", "OPTIONS"}
SEEN_INTERVAL = timedelta(minutes=5)
SERVICE_ACTOR_HEADER = "x-admin-actor"
SERVICE_ACTOR_NAME_HEADER = "x-admin-actor-name"  # percent-encoded UTF-8
SERVICE_ACTOR = re.compile(r"^[A-Za-z0-9._@:+-]{1,128}$")


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _s256(verifier: str) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()


def _safe_return_to(value: str | None) -> str:
    if value and value.startswith("/admin") and not value.startswith("//") and "\\" not in value:
        return value
    return "/admin/"


class TokenRequest(BaseModel):
    code: str
    code_verifier: str


class Authenticator:
    def __init__(self, settings: Settings, database: Database) -> None:
        self.settings = settings
        self.database = database
        self._http = httpx.Client(timeout=15)
        self._discovery: dict | None = None
        self._discovered_at = 0.0

    def close(self) -> None:
        self._http.close()

    # ------------------------------------------------------------- request auth

    def principal(self, request: Request) -> Principal | None:
        header = request.headers.get("authorization", "")
        scheme, _, token = header.partition(" ")
        if scheme.lower() == "bearer" and token:
            service = self._service_principal(token, request)
            return service or self._bearer_principal(token)
        cookie = request.cookies.get(ADMIN_COOKIE)
        if cookie:
            return self._console_principal(cookie, request)
        return None

    def _service_principal(self, token: str, request: Request) -> Principal | None:
        """A trusted platform acting for one of its signed-in people; the audit log names that person."""
        match = next((value for key, value in self.settings.service_tokens.items()
                      if hmac.compare_digest(token, key)), None)
        if match is None:
            return None
        service, role = match
        actor = request.headers.get(SERVICE_ACTOR_HEADER, "")
        if not SERVICE_ACTOR.match(actor):
            raise HTTPException(401, f"{service} 的请求必须在 X-Admin-Actor 头里注明操作人",
                                headers={"WWW-Authenticate": "Bearer"})
        name = unquote(request.headers.get(SERVICE_ACTOR_NAME_HEADER, ""))[:100].strip() or actor
        return Principal(f"{service}:{actor}", f"{name}（{service}）", role, "service")

    def _bearer_principal(self, token: str) -> Principal:
        if self.settings.admin_api_token and hmac.compare_digest(token, self.settings.admin_api_token):
            return Principal(subject="admin-api-token", name="管理脚本", role="superadmin", via="token")
        with self.database.connection() as conn:
            row = conn.execute(
                """
                UPDATE device_sessions SET last_used_at = now(), expires_at = now() + %s
                WHERE token_hash = %s AND revoked_at IS NULL AND expires_at > now()
                RETURNING subject, display_name, is_admin
                """,
                (timedelta(days=self.settings.session_days), _hash(token)),
            ).fetchone()
            if row is None:
                raise HTTPException(401, "登录已失效，请重新登录", headers={"WWW-Authenticate": "Bearer"})
            user = conn.execute("SELECT * FROM users WHERE subject = %s", (row["subject"],)).fetchone()
            if user is None:
                user = accounts.upsert_user(conn, row["subject"], row["display_name"], None,
                                            "superadmin" if row["is_admin"] else "none")
            self._check_active(user)
            self._touch(conn, user)
        return Principal(user["subject"], user["display_name"] or row["display_name"],
                         effective_role(user, self.settings.admin_subjects), "device")

    def _console_principal(self, cookie: str, request: Request) -> Principal | None:
        with self.database.connection() as conn:
            user = conn.execute(
                """
                SELECT u.*, s.last_used_at AS session_used_at FROM admin_sessions s
                JOIN users u USING (subject)
                WHERE s.token_hash = %s AND s.expires_at > now()
                """,
                (_hash(cookie),),
            ).fetchone()
            if user is None:
                return None
            self._check_active(user)
            if request.method not in SAFE_METHODS and request.headers.get(ADMIN_HEADER) != "1":
                raise HTTPException(403, "请求缺少 X-MKread-Admin 头（防跨站请求）")
            if user["session_used_at"] < datetime.now(timezone.utc) - SEEN_INTERVAL:
                conn.execute("UPDATE admin_sessions SET last_used_at = now() WHERE token_hash = %s", (_hash(cookie),))
            self._touch(conn, user)
        return Principal(user["subject"], user["display_name"],
                         effective_role(user, self.settings.admin_subjects), "console")

    @staticmethod
    def _check_active(user: dict) -> None:
        if user["status"] != "active":
            raise HTTPException(401, "账号已停用，请联系管理员", headers={"WWW-Authenticate": "Bearer"})

    @staticmethod
    def _touch(conn, user: dict) -> None:
        if user["last_seen_at"] is None or user["last_seen_at"] < datetime.now(timezone.utc) - SEEN_INTERVAL:
            conn.execute("UPDATE users SET last_seen_at = now() WHERE subject = %s", (user["subject"],))

    def require_reader(self, request: Request) -> Principal:
        principal = self.principal(request)
        if principal is None:
            if self.settings.read_access == "public":
                return ANONYMOUS
            raise HTTPException(401, "需要登录", headers={"WWW-Authenticate": "Bearer"})
        return principal

    def require_role(self, request: Request, role: str) -> Principal:
        principal = self.principal(request)
        if principal is None:
            raise HTTPException(401, "需要登录", headers={"WWW-Authenticate": "Bearer"})
        if not role_at_least(principal.role, role):
            raise HTTPException(403, f"需要{ROLE_NAMES[role]}权限")
        return principal

    def require_admin(self, request: Request) -> Principal:
        principal = self.principal(request)
        if principal is None:
            raise HTTPException(401, "需要登录", headers={"WWW-Authenticate": "Bearer"})
        if not principal.is_admin:
            raise HTTPException(403, "没有发布权限")
        return principal

    # ------------------------------------------------------------- MKauth

    def _oidc(self) -> dict:
        if self._discovery is None or time.monotonic() - self._discovered_at > 3600:
            response = self._http.get(f"{self.settings.oidc_issuer}/.well-known/openid-configuration")
            response.raise_for_status()
            self._discovery = response.json()
            self._discovered_at = time.monotonic()
        return self._discovery

    def _callback_url(self) -> str:
        return f"{self.settings.public_base_url}/api/v1/auth/callback"

    def _authorize_redirect(self, server_state: str, server_verifier: str) -> RedirectResponse:
        query = urlencode(
            {
                "response_type": "code",
                "client_id": self.settings.oidc_client_id,
                "redirect_uri": self._callback_url(),
                "scope": self.settings.oidc_scopes,
                "state": server_state,
                "code_challenge": _s256(server_verifier),
                "code_challenge_method": "S256",
            }
        )
        return RedirectResponse(f"{self._oidc()['authorization_endpoint']}?{query}", status_code=302)

    def _redeem(self, code: str, server_verifier: str) -> dict:
        """Exchanges the MKauth code and returns the userinfo claims."""
        oidc = self._oidc()
        token = self._http.post(
            oidc["token_endpoint"],
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": self._callback_url(),
                "code_verifier": server_verifier,
            },
            auth=(self.settings.oidc_client_id, self.settings.oidc_client_secret),
        )
        if token.status_code != 200:
            raise HTTPException(502, "MKauth 登录校验失败，请重试")
        profile = self._http.get(
            oidc["userinfo_endpoint"],
            headers={"Authorization": f"Bearer {token.json()['access_token']}"},
        )
        if profile.status_code != 200 or not profile.json().get("sub"):
            raise HTTPException(502, "无法读取 MKauth 账号信息")
        return profile.json()

    def _sign_in_user(self, conn, claims: dict) -> dict:
        return accounts.upsert_user(
            conn,
            str(claims["sub"]),
            claims.get("name") or claims.get("preferred_username") or claims.get("email"),
            claims.get("email"),
            accounts.claim_role(accounts.claim_values(claims)),
        )

    def router(self) -> APIRouter:
        router = APIRouter(prefix="/api/v1/auth")
        settings = self.settings

        def new_request(conn, **fields) -> tuple[str, str]:
            server_state = secrets.token_urlsafe(32)
            server_verifier = secrets.token_urlsafe(48)
            conn.execute("DELETE FROM auth_requests WHERE created_at < now() - %s", (timedelta(seconds=AUTH_REQUEST_TTL),))
            conn.execute(
                "INSERT INTO auth_requests (state, server_verifier, purpose, app_port, app_state, app_challenge, return_to) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s)",
                (server_state, server_verifier, fields.get("purpose", "app"), fields.get("app_port"),
                 fields.get("app_state"), fields.get("app_challenge"), fields.get("return_to")),
            )
            return server_state, server_verifier

        @router.get("/start")
        def start(
            port: int = Query(..., ge=1024, le=65535),
            state: str = Query(..., min_length=16, max_length=128),
            code_challenge: str = Query(..., min_length=43, max_length=128),
        ):
            if not settings.login_enabled:
                raise HTTPException(503, "服务器尚未配置 MKauth 登录")
            with self.database.connection() as conn:
                server_state, server_verifier = new_request(
                    conn, app_port=port, app_state=state, app_challenge=code_challenge)
            return self._authorize_redirect(server_state, server_verifier)

        @router.get("/admin/start")
        def admin_start(return_to: str | None = None):
            if not settings.login_enabled:
                raise HTTPException(503, "服务器尚未配置 MKauth 登录")
            with self.database.connection() as conn:
                server_state, server_verifier = new_request(
                    conn, purpose="admin", return_to=_safe_return_to(return_to))
            return self._authorize_redirect(server_state, server_verifier)

        @router.get("/callback")
        def callback(request: Request, state: str, code: str | None = None, error: str | None = None):
            with self.database.connection() as conn:
                pending = conn.execute(
                    "DELETE FROM auth_requests WHERE state = %s AND created_at > now() - %s RETURNING *",
                    (state, timedelta(seconds=AUTH_REQUEST_TTL)),
                ).fetchone()
            if pending is None:
                raise HTTPException(400, "登录请求已过期，请重新登录")
            if pending["purpose"] == "admin":
                return admin_callback(request, pending, code, error)

            app_redirect = f"http://127.0.0.1:{pending['app_port']}/callback"

            def back_to_app(**params) -> RedirectResponse:
                return RedirectResponse(
                    f"{app_redirect}?{urlencode({'state': pending['app_state'], **params})}", status_code=302)

            if error or not code:
                return back_to_app(error=error or "access_denied")
            claims = self._redeem(code, pending["server_verifier"])
            with self.database.connection() as conn, conn.transaction():
                user = self._sign_in_user(conn, claims)
                if user["status"] != "active":
                    return back_to_app(error="account_disabled")
                app_code = secrets.token_urlsafe(32)
                conn.execute(
                    "INSERT INTO auth_codes (code_hash, app_challenge, subject, display_name, is_admin) "
                    "VALUES (%s, %s, %s, %s, %s)",
                    (
                        _hash(app_code),
                        pending["app_challenge"],
                        user["subject"],
                        user["display_name"],
                        role_at_least(effective_role(user, settings.admin_subjects), "operator"),
                    ),
                )
            return back_to_app(code=app_code)

        def admin_callback(request: Request, pending: dict, code: str | None, error: str | None):
            def back_to_console(login_error: str) -> RedirectResponse:
                return RedirectResponse(f"/admin/?{urlencode({'login_error': login_error})}", status_code=302)

            if error or not code:
                return back_to_console(error or "access_denied")
            claims = self._redeem(code, pending["server_verifier"])
            with self.database.connection() as conn, conn.transaction():
                user = self._sign_in_user(conn, claims)
                if user["status"] != "active":
                    return back_to_console("account_disabled")
                if effective_role(user, settings.admin_subjects) == "none":
                    return back_to_console("no_role")
                session_token = secrets.token_urlsafe(32)
                conn.execute(
                    "INSERT INTO admin_sessions (token_hash, subject, ip, user_agent, expires_at) "
                    "VALUES (%s, %s, %s, %s, now() + %s)",
                    (
                        _hash(session_token),
                        user["subject"],
                        request.headers.get("cf-connecting-ip") or (request.client.host if request.client else None),
                        (request.headers.get("user-agent") or "")[:200],
                        ADMIN_SESSION_TTL,
                    ),
                )
                conn.execute("DELETE FROM admin_sessions WHERE expires_at < now()")
            response = RedirectResponse(_safe_return_to(pending["return_to"]), status_code=302)
            response.set_cookie(
                ADMIN_COOKIE,
                session_token,
                max_age=int(ADMIN_SESSION_TTL.total_seconds()),
                httponly=True,
                secure=settings.public_base_url.startswith("https://"),
                samesite="lax",
                path="/",
            )
            return response

        @router.post("/admin/logout", status_code=204)
        def admin_logout(request: Request) -> Response:
            cookie = request.cookies.get(ADMIN_COOKIE)
            if cookie:
                with self.database.connection() as conn:
                    conn.execute("DELETE FROM admin_sessions WHERE token_hash = %s", (_hash(cookie),))
            response = Response(status_code=204)
            response.delete_cookie(ADMIN_COOKIE, path="/")
            return response

        @router.post("/token")
        def token(body: TokenRequest, request: Request) -> dict:
            with self.database.connection() as conn, conn.transaction():
                row = conn.execute(
                    "DELETE FROM auth_codes WHERE code_hash = %s AND created_at > now() - %s RETURNING *",
                    (_hash(body.code), timedelta(seconds=APP_CODE_TTL)),
                ).fetchone()
                if row is None or not hmac.compare_digest(_s256(body.code_verifier), row["app_challenge"]):
                    raise HTTPException(400, "登录码无效或已过期")
                device_token = secrets.token_urlsafe(48)
                expires = datetime.now(timezone.utc) + timedelta(days=settings.session_days)
                conn.execute(
                    "INSERT INTO device_sessions (token_hash, subject, display_name, is_admin, user_agent, expires_at) "
                    "VALUES (%s, %s, %s, %s, %s, %s)",
                    (
                        _hash(device_token),
                        row["subject"],
                        row["display_name"],
                        row["is_admin"],
                        (request.headers.get("user-agent") or "")[:200],
                        expires,
                    ),
                )
            return {
                "token": device_token,
                "expiresAt": expires.isoformat(),
                "subject": row["subject"],
                "name": row["display_name"],
                "isAdmin": row["is_admin"],
            }

        @router.post("/logout", status_code=204)
        def logout(request: Request) -> None:
            header = request.headers.get("authorization", "")
            scheme, _, device_token = header.partition(" ")
            if scheme.lower() == "bearer" and device_token:
                with self.database.connection() as conn:
                    conn.execute(
                        "UPDATE device_sessions SET revoked_at = now() WHERE token_hash = %s", (_hash(device_token),)
                    )

        return router
