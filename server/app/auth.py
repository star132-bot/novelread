"""MKread sign-in through MKauth, and device sessions for the app.

Flow (app ↔ this server ↔ MKauth):

1. The app opens  GET /api/v1/auth/start?port=P&state=S&code_challenge=C  in a browser tab.
   P is a loopback port the app listens on; C is the app's PKCE S256 challenge.
2. This server redirects to MKauth (authorization code + its own PKCE). It is a confidential
   client, so the client secret never leaves the server.
3. MKauth redirects back to /api/v1/auth/callback. The server redeems the code, reads the
   user's profile, and redirects the browser to http://127.0.0.1:P/callback with a one-time
   code bound to C.
4. The app posts that code with its PKCE verifier to /api/v1/auth/token and receives a
   long-lived device token used for catalog sync and downloads.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode

import httpx
from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import RedirectResponse
from pydantic import BaseModel

from .config import Settings
from .db import Database


@dataclass(frozen=True)
class Principal:
    subject: str
    name: str | None
    is_admin: bool


ANONYMOUS = Principal(subject="anonymous", name=None, is_admin=False)
AUTH_REQUEST_TTL = 600
APP_CODE_TTL = 120


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _s256(verifier: str) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()


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
        if scheme.lower() != "bearer" or not token:
            return None
        if self.settings.admin_api_token and hmac.compare_digest(token, self.settings.admin_api_token):
            return Principal(subject="admin-api-token", name="管理脚本", is_admin=True)
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
        return Principal(row["subject"], row["display_name"], row["is_admin"])

    def require_reader(self, request: Request) -> Principal:
        principal = self.principal(request)
        if principal is None:
            if self.settings.read_access == "public":
                return ANONYMOUS
            raise HTTPException(401, "需要登录", headers={"WWW-Authenticate": "Bearer"})
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

    def router(self) -> APIRouter:
        router = APIRouter(prefix="/api/v1/auth")
        settings = self.settings
        callback_url = f"{settings.public_base_url}/api/v1/auth/callback"

        @router.get("/start")
        def start(
            port: int = Query(..., ge=1024, le=65535),
            state: str = Query(..., min_length=16, max_length=128),
            code_challenge: str = Query(..., min_length=43, max_length=128),
        ):
            if not settings.login_enabled:
                raise HTTPException(503, "服务器尚未配置 MKauth 登录")
            server_state = secrets.token_urlsafe(32)
            server_verifier = secrets.token_urlsafe(48)
            with self.database.connection() as conn:
                conn.execute("DELETE FROM auth_requests WHERE created_at < now() - %s", (timedelta(seconds=AUTH_REQUEST_TTL),))
                conn.execute(
                    "INSERT INTO auth_requests (state, server_verifier, app_port, app_state, app_challenge) "
                    "VALUES (%s, %s, %s, %s, %s)",
                    (server_state, server_verifier, port, state, code_challenge),
                )
            query = urlencode(
                {
                    "response_type": "code",
                    "client_id": settings.oidc_client_id,
                    "redirect_uri": callback_url,
                    "scope": settings.oidc_scopes,
                    "state": server_state,
                    "code_challenge": _s256(server_verifier),
                    "code_challenge_method": "S256",
                }
            )
            return RedirectResponse(f"{self._oidc()['authorization_endpoint']}?{query}", status_code=302)

        @router.get("/callback")
        def callback(state: str, code: str | None = None, error: str | None = None):
            with self.database.connection() as conn:
                pending = conn.execute(
                    "DELETE FROM auth_requests WHERE state = %s AND created_at > now() - %s RETURNING *",
                    (state, timedelta(seconds=AUTH_REQUEST_TTL)),
                ).fetchone()
            if pending is None:
                raise HTTPException(400, "登录请求已过期，请回到 App 重新登录")
            app_redirect = f"http://127.0.0.1:{pending['app_port']}/callback"
            if error or not code:
                return RedirectResponse(
                    f"{app_redirect}?{urlencode({'state': pending['app_state'], 'error': error or 'access_denied'})}",
                    status_code=302,
                )
            oidc = self._oidc()
            token = self._http.post(
                oidc["token_endpoint"],
                data={
                    "grant_type": "authorization_code",
                    "code": code,
                    "redirect_uri": callback_url,
                    "code_verifier": pending["server_verifier"],
                },
                auth=(settings.oidc_client_id, settings.oidc_client_secret),
            )
            if token.status_code != 200:
                raise HTTPException(502, "MKauth 登录校验失败，请重试")
            profile = self._http.get(
                oidc["userinfo_endpoint"],
                headers={"Authorization": f"Bearer {token.json()['access_token']}"},
            )
            if profile.status_code != 200 or not profile.json().get("sub"):
                raise HTTPException(502, "无法读取 MKauth 账号信息")
            claims = profile.json()
            subject = str(claims["sub"])
            roles = claims.get("roles") or []
            is_admin = subject in settings.admin_subjects or "mkread:admin" in roles
            app_code = secrets.token_urlsafe(32)
            with self.database.connection() as conn:
                conn.execute(
                    "INSERT INTO auth_codes (code_hash, app_challenge, subject, display_name, is_admin) "
                    "VALUES (%s, %s, %s, %s, %s)",
                    (
                        _hash(app_code),
                        pending["app_challenge"],
                        subject,
                        claims.get("name") or claims.get("preferred_username") or claims.get("email"),
                        is_admin,
                    ),
                )
            return RedirectResponse(
                f"{app_redirect}?{urlencode({'code': app_code, 'state': pending['app_state']})}",
                status_code=302,
            )

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
