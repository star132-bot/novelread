"""Admin console sign-in, RBAC, audit and the admin data API (needs TEST_DATABASE_URL, see test_auth_flow.py)."""
import hashlib
import os
import secrets
import sys
import tempfile
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import httpx

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "server"), str(ROOT / "tools" / "mkbook")]

from fastapi.testclient import TestClient  # noqa: E402

import mkbook  # noqa: E402
from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402

ISSUER = "https://auth.example.test"
ADMIN = {"X-MKread-Admin": "1"}
SERVICE_TOKEN = "s" * 40


def fake_mkauth(userinfo: dict):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/.well-known/openid-configuration":
            return httpx.Response(200, json={
                "authorization_endpoint": f"{ISSUER}/oauth2/authorize",
                "token_endpoint": f"{ISSUER}/oauth2/token",
                "userinfo_endpoint": f"{ISSUER}/userinfo",
            })
        if request.url.path == "/oauth2/token":
            return httpx.Response(200, json={"access_token": "mk-access", "token_type": "Bearer"})
        if request.url.path == "/userinfo":
            return httpx.Response(200, json=userinfo)
        return httpx.Response(404)
    return handler


def build_book(directory: Path, book_id: str, revision: int = 1, title: str = "夜行者") -> Path:
    book = mkbook.Book(id=book_id, title=title, author="林默", revision=revision, tags=["测试"], chapters=[
        mkbook.Chapter(title="第1章 夜访", text="夜色很深。" * 20, volume="第一卷"),
        mkbook.Chapter(title="第2章 归来", text="天亮了。" * 30, volume="第一卷"),
    ])
    mkbook.assign_chapter_ids(book, None)
    out = directory / f"{book_id}-r{revision}.mkbook"
    mkbook.write_package(book, out)
    return out


@unittest.skipUnless(os.environ.get("TEST_DATABASE_URL"), "TEST_DATABASE_URL not set")
class AdminTest(unittest.TestCase):
    def setUp(self):
        self.data_dir = Path(tempfile.mkdtemp())
        settings = Settings(
            database_url=os.environ["TEST_DATABASE_URL"],
            data_dir=self.data_dir,
            public_base_url="https://books.example.test",
            oidc_issuer=ISSUER,
            oidc_client_id="mkread-server",
            oidc_client_secret="s3cret",
            read_access="login",
            admin_subjects=["root-1"],
            admin_api_token="",
            admin_service_tokens=[f"server-hub:operator:{SERVICE_TOKEN}"],
        )
        self.app = create_app(settings)
        self.client = TestClient(self.app, follow_redirects=False).__enter__()
        self.database = self.app.state.authenticator.database
        with self.database.connection() as conn:
            conn.execute("TRUNCATE admin_audit, admin_sessions, users, device_sessions, book_revisions, books CASCADE")

    def tearDown(self):
        self.client.__exit__(None, None, None)

    # ---------------------------------------------------------------- helpers

    def mkauth(self, userinfo: dict) -> None:
        self.app.state.authenticator._http = httpx.Client(transport=httpx.MockTransport(fake_mkauth(userinfo)))

    def console_login(self, userinfo: dict, return_to: str = "/admin/#/users") -> httpx.Response:
        self.mkauth(userinfo)
        start = self.client.get("/api/v1/auth/admin/start", params={"return_to": return_to})
        self.assertEqual(302, start.status_code)
        state = parse_qs(urlparse(start.headers["location"]).query)["state"][0]
        return self.client.get("/api/v1/auth/callback", params={"state": state, "code": "mk-code"})

    def user(self, subject: str, role: str = "none", name: str | None = None) -> dict:
        """Creates a user with a console cookie and a device token."""
        cookie, device = secrets.token_urlsafe(16), secrets.token_urlsafe(16)
        with self.database.connection() as conn:
            conn.execute("INSERT INTO users (subject, display_name, role, last_seen_at) VALUES (%s, %s, %s, now())",
                         (subject, name or subject, role))
            conn.execute("INSERT INTO admin_sessions (token_hash, subject, expires_at) VALUES (%s, %s, now() + interval '1 hour')",
                         (hashlib.sha256(cookie.encode()).hexdigest(), subject))
            conn.execute("INSERT INTO device_sessions (token_hash, subject, display_name, expires_at) "
                         "VALUES (%s, %s, %s, now() + interval '1 day')",
                         (hashlib.sha256(device.encode()).hexdigest(), subject, name or subject))
        return {"console": {"Cookie": f"mkread_admin={cookie}", **ADMIN}, "device": {"Authorization": f"Bearer {device}"}}

    def audit(self, action: str) -> list[dict]:
        with self.database.connection() as conn:
            return conn.execute("SELECT * FROM admin_audit WHERE action = %s ORDER BY id", (action,)).fetchall()

    # ---------------------------------------------------------------- sign-in

    def test_console_sign_in_sets_cookie_and_keeps_one_user_per_subject(self):
        callback = self.console_login({"sub": "op-7", "name": "运营小王", "email": "w@example.test",
                                       "roles": ["mkread:operator"]})
        self.assertEqual(302, callback.status_code)
        self.assertEqual("/admin/#/users", callback.headers["location"])
        set_cookie = callback.headers["set-cookie"]
        for flag in ("HttpOnly", "Secure", "SameSite=lax"):
            self.assertIn(flag.lower(), set_cookie.lower())
        cookie = set_cookie.split(";")[0]
        me = self.client.get("/api/v1/admin/session", headers={"Cookie": cookie}).json()
        self.assertEqual(("op-7", "operator"), (me["subject"], me["role"]))
        self.assertIn("books.write", me["permissions"])
        self.assertNotIn("releases.write", me["permissions"])

        self.console_login({"sub": "op-7", "name": "运营小王", "roles": ["mkread:operator"]})
        with self.database.connection() as conn:
            self.assertEqual(1, conn.execute("SELECT count(*) AS n FROM users WHERE subject = 'op-7'").fetchone()["n"])

    def test_console_rejects_accounts_without_admin_role_and_unsafe_return_urls(self):
        no_role = self.console_login({"sub": "reader-1", "name": "读者"})
        self.assertEqual("/admin/?login_error=no_role", no_role.headers["location"])
        self.assertNotIn("set-cookie", no_role.headers)
        evil = self.console_login({"sub": "root-1", "name": "站长"}, return_to="//evil.example/admin")
        self.assertEqual("/admin/", evil.headers["location"])

    def test_writes_need_the_csrf_header(self):
        root = self.user("root-1")
        self.user("victim")
        cookie_only = {"Cookie": root["console"]["Cookie"]}
        body = {"status": "disabled", "reason": "测试跨站请求"}
        blocked = self.client.patch("/api/v1/admin/users/victim", json=body, headers=cookie_only)
        self.assertEqual(403, blocked.status_code)
        self.assertEqual("forbidden", blocked.json()["code"])
        allowed = self.client.patch("/api/v1/admin/users/victim", json=body, headers=root["console"])
        self.assertEqual(200, allowed.status_code, allowed.text)

    # ---------------------------------------------------------------- users and RBAC

    def test_disabling_a_user_cuts_off_their_app_immediately_and_is_audited(self):
        operator = self.user("op-1", "operator", "运营")
        reader = self.user("reader-1", name="读者甲")
        self.assertEqual(200, self.client.get("/api/v1/me", headers=reader["device"]).status_code)

        response = self.client.patch("/api/v1/admin/users/reader-1",
                                     json={"status": "disabled", "reason": "违规传播盗版"}, headers=operator["console"])
        self.assertEqual("disabled", response.json()["status"])
        blocked = self.client.get("/api/v1/me", headers=reader["device"])
        self.assertEqual(401, blocked.status_code)
        self.assertIn("停用", blocked.json()["detail"])

        [entry] = self.audit("user.disable")
        self.assertEqual(("op-1", "operator", "reader-1", "违规传播盗版"),
                         (entry["actor_subject"], entry["actor_role"], entry["resource_id"], entry["reason"]))
        self.assertEqual(({"status": "active"}, {"status": "disabled"}), (entry["before"], entry["after"]))

        again = self.client.patch("/api/v1/admin/users/reader-1",
                                  json={"status": "disabled", "reason": "重复操作"}, headers=operator["console"])
        self.assertEqual((409, "no_change"), (again.status_code, again.json()["code"]))

    def test_roles_limit_what_each_admin_can_do(self):
        viewer = self.user("viewer-1", "viewer")
        operator = self.user("op-1", "operator")
        self.user("op-2", "operator")
        self.user("reader-1")

        listed = self.client.get("/api/v1/admin/users", headers=viewer["console"])
        self.assertEqual(200, listed.status_code)
        denied = self.client.patch("/api/v1/admin/users/reader-1", json={"quota_bytes": 1, "reason": "只读试试"},
                                   headers=viewer["console"])
        self.assertEqual((403, "forbidden"), (denied.status_code, denied.json()["code"]))

        role = self.client.patch("/api/v1/admin/users/reader-1", json={"role": "viewer", "reason": "提升权限"},
                                 headers=operator["console"])
        self.assertEqual(403, role.status_code, "only superadmins assign roles")
        peer = self.client.patch("/api/v1/admin/users/op-2", json={"status": "disabled", "reason": "同级停用"},
                                 headers=operator["console"])
        self.assertEqual(403, peer.status_code, "operators cannot disable other admins")
        own = self.client.patch("/api/v1/admin/users/op-1", json={"status": "disabled", "reason": "停用自己"},
                                headers=operator["console"])
        self.assertEqual((409, "self_change"), (own.status_code, own.json()["code"]))

        root = self.user("root-1")  # in ADMIN_SUBJECTS
        promoted = self.client.patch("/api/v1/admin/users/reader-1", json={"role": "operator", "reason": "新同事"},
                                     headers=root["console"])
        self.assertEqual(("operator", "console"), (promoted.json()["role"], promoted.json()["role_source"]))

        superadmins = self.client.get("/api/v1/admin/users", params={"role": "superadmin"}, headers=viewer["console"])
        self.assertEqual(["root-1"], [u["subject"] for u in superadmins.json()["items"]])

    def test_revoking_sessions_signs_the_user_out_everywhere(self):
        operator = self.user("op-1", "operator")
        reader = self.user("reader-1")
        revoked = self.client.post("/api/v1/admin/users/reader-1/revoke-sessions",
                                   json={"reason": "手机丢失"}, headers=operator["console"])
        self.assertEqual(1, revoked.json()["revoked"])
        self.assertEqual(401, self.client.get("/api/v1/me", headers=reader["device"]).status_code)
        detail = self.client.get("/api/v1/admin/users/reader-1", headers=operator["console"]).json()
        self.assertEqual(["revoked"], [s["state"] for s in detail["sessions"]])
        self.assertEqual("user.revoke_sessions", detail["audit"][0]["action"])

    def test_trusted_platform_acts_for_a_named_person(self):
        self.user("reader-1")
        service = {"Authorization": f"Bearer {SERVICE_TOKEN}"}
        anonymous = self.client.get("/api/v1/admin/users", headers=service)
        self.assertEqual((401, "unauthorized"), (anonymous.status_code, anonymous.json()["code"]))

        acting = {**service, "X-Admin-Actor": "mkfield", "X-Admin-Actor-Name": "%E6%98%9F%E9%87%8E"}
        session = self.client.get("/api/v1/admin/session", headers=acting).json()
        self.assertEqual(("server-hub:mkfield", "星野（server-hub）", "operator", "service"),
                         (session["subject"], session["name"], session["role"], session["via"]))
        changed = self.client.patch("/api/v1/admin/users/reader-1",
                                    json={"status": "disabled", "reason": "在 Server Hub 里停用"}, headers=acting)
        self.assertEqual(200, changed.status_code, changed.text)
        [entry] = self.audit("user.disable")
        self.assertEqual(("server-hub:mkfield", "星野（server-hub）"), (entry["actor_subject"], entry["actor_name"]))
        # The configured role caps what the platform may do.
        self.assertEqual(403, self.client.post("/api/v1/admin/releases/1/withdraw", json={"reason": "越权"},
                                               headers=acting).status_code)

    # ---------------------------------------------------------------- lists and errors

    def test_lists_paginate_sort_filter_and_export_csv(self):
        viewer = self.user("viewer-1", "viewer", "Zed")
        for index in range(5):
            self.user(f"reader-{index}", name=f"读者{index}")
        page = self.client.get("/api/v1/admin/users", params={"page": 2, "page_size": 2, "sort": "display_name"},
                               headers=viewer["console"]).json()
        self.assertEqual((6, 2, 2), (page["total"], page["page"], page["page_size"]))
        # "Zed" sorts before the Chinese names, so page 2 starts at the second reader.
        self.assertEqual(["读者1", "读者2"], [u["display_name"] for u in page["items"]])

        found = self.client.get("/api/v1/admin/users", params={"q": "读者4"}, headers=viewer["console"]).json()
        self.assertEqual(["reader-4"], [u["subject"] for u in found["items"]])

        csv = self.client.get("/api/v1/admin/users", params={"format": "csv"}, headers=viewer["console"])
        self.assertTrue(csv.headers["content-type"].startswith("text/csv"))
        self.assertIn("attachment", csv.headers["content-disposition"])
        lines = csv.content.decode("utf-8-sig").splitlines()
        self.assertTrue(lines[0].startswith("Subject,名称"))
        self.assertEqual(7, len(lines))

    def test_errors_use_one_format(self):
        viewer = self.user("viewer-1", "viewer")
        missing = self.client.get("/api/v1/admin/users/nobody", headers=viewer["console"])
        self.assertEqual({"code": "user_not_found", "message": "用户不存在", "details": None}, missing.json())
        bad_sort = self.client.get("/api/v1/admin/users", params={"sort": "password"}, headers=viewer["console"])
        self.assertEqual("invalid_sort", bad_sort.json()["code"])
        invalid = self.client.get("/api/v1/admin/users", params={"page": 0}, headers=viewer["console"])
        self.assertEqual(("invalid_request", "page"), (invalid.json()["code"], invalid.json()["details"][0]["field"]))
        anonymous = self.client.get("/api/v1/admin/overview")
        self.assertEqual((401, "unauthorized"), (anonymous.status_code, anonymous.json()["code"]))
        # The app's own endpoints keep FastAPI's format.
        self.assertIn("detail", self.client.get("/api/v1/catalog").json())

    def test_openapi_is_for_admins_only(self):
        self.assertEqual(404, self.client.get("/openapi.json").status_code)
        self.assertEqual(401, self.client.get("/api/v1/admin/openapi.json").status_code)
        viewer = self.user("viewer-1", "viewer")
        schema = self.client.get("/api/v1/admin/openapi.json", headers=viewer["console"]).json()
        self.assertIn("/api/v1/admin/users", schema["paths"])
        self.assertEqual(302, self.client.get("/admin/api-docs").status_code)
        self.assertEqual(200, self.client.get("/admin/api-docs", headers=viewer["console"]).status_code)

    # ---------------------------------------------------------------- books

    def test_book_publish_unpublish_restore_and_batch_are_audited(self):
        operator = self.user("op-1", "operator", "运营")
        viewer = self.user("viewer-1", "viewer")
        package = build_book(self.data_dir, "yexing-zhe")
        with package.open("rb") as fh:
            uploaded = self.client.post("/api/v1/admin/books", data={"reason": "首发"},
                                        files={"file": ("book.mkbook", fh, mkbook.MIMETYPE)}, headers=operator["console"])
        self.assertEqual(201, uploaded.status_code, uploaded.text)
        with package.open("rb") as fh:
            again = self.client.post("/api/v1/admin/books", data={"reason": "重复上传"},
                                     files={"file": ("book.mkbook", fh, mkbook.MIMETYPE)}, headers=operator["console"])
        self.assertEqual((409, "conflict"), (again.status_code, again.json()["code"]))
        with package.open("rb") as fh:
            readonly = self.client.post("/api/v1/admin/books", data={"reason": "只读上传"},
                                        files={"file": ("book.mkbook", fh, mkbook.MIMETYPE)}, headers=viewer["console"])
        self.assertEqual(403, readonly.status_code)

        detail = self.client.get("/api/v1/admin/books/yexing-zhe", headers=viewer["console"]).json()
        self.assertEqual(([{"name": "第一卷", "chapters": 2, "chars": 220}], 1),
                         (detail["volumes"], len(detail["revision_history"])))

        down = self.client.post("/api/v1/admin/books/yexing-zhe/unpublish", json={"reason": "版权投诉"},
                                headers=operator["console"])
        self.assertEqual("deleted", down.json()["state"])
        deleted = self.client.get("/api/v1/admin/books", params={"state": "deleted"}, headers=viewer["console"]).json()
        self.assertEqual(["yexing-zhe"], [b["id"] for b in deleted["items"]])

        build_book(self.data_dir, "second-book", title="第二本")
        with (self.data_dir / "second-book-r1.mkbook").open("rb") as fh:
            self.client.post("/api/v1/admin/books", data={"reason": "上新"},
                             files={"file": ("b.mkbook", fh, mkbook.MIMETYPE)}, headers=operator["console"])
        batch = self.client.post("/api/v1/admin/books/batch",
                                 json={"action": "restore", "ids": ["yexing-zhe", "second-book", "ghost-book"],
                                       "reason": "投诉撤回"},
                                 headers=operator["console"]).json()
        self.assertEqual([True, False, False], [r["ok"] for r in batch["results"]])

        self.assertEqual(["首发", "上新"], [e["reason"] for e in self.audit("book.publish")])
        self.assertEqual(["版权投诉"], [e["reason"] for e in self.audit("book.unpublish")])
        self.assertEqual(["投诉撤回"], [e["reason"] for e in self.audit("book.restore")])
        audit = self.client.get("/api/v1/admin/audit", params={"action": "book.", "resource_id": "yexing-zhe"},
                                headers=viewer["console"]).json()
        self.assertEqual(["book.restore", "book.unpublish", "book.publish"], [e["action"] for e in audit["items"]])

        overview = self.client.get("/api/v1/admin/overview", headers=viewer["console"]).json()
        self.assertEqual((2, 0), (overview["books"]["active"], overview["books"]["deleted"]))
        self.assertEqual(14, len(overview["daily"]))
        # Sums must be JSON numbers (PostgreSQL returns numeric for SUM over bigint).
        self.assertIsInstance(overview["storage"]["books"], int)
        self.assertIsInstance(overview["books"]["chars"], int)

        hits = self.client.get("/api/v1/admin/search", params={"q": "第二"}, headers=viewer["console"]).json()
        self.assertEqual(["second-book"], [b["id"] for b in hits["books"]])

    def test_releases_need_superadmin(self):
        operator = self.user("op-1", "operator")
        root = self.user("root-1")
        with self.database.connection() as conn:
            conn.execute("DELETE FROM app_releases")
            conn.execute("INSERT INTO app_releases (version_code, version_name, apk_path, apk_size, apk_sha256) "
                         "VALUES (3, '0.3.0', 'releases/x.apk', 10, %s)", ("a" * 64,))
        denied = self.client.post("/api/v1/admin/releases/3/withdraw", json={"reason": "崩溃"}, headers=operator["console"])
        self.assertEqual(403, denied.status_code)
        withdrawn = self.client.post("/api/v1/admin/releases/3/withdraw", json={"reason": "启动崩溃"}, headers=root["console"])
        self.assertEqual("withdrawn", withdrawn.json()["state"])
        self.assertFalse(self.client.get("/api/v1/app/latest").json()["available"])
        restored = self.client.post("/api/v1/admin/releases/3/restore", json={"reason": "已修复"}, headers=root["console"])
        self.assertTrue(restored.json()["is_latest"])
        forced = self.client.patch("/api/v1/admin/releases/3", json={"min_supported": 3, "reason": "强制升级"},
                                   headers=root["console"])
        self.assertEqual(3, forced.json()["min_supported"])
        self.assertTrue(self.client.get("/api/v1/app/latest", params={"current": 2}).json()["mandatory"])
        self.assertEqual(3, len(self.client.get("/api/v1/admin/audit", params={"resource_type": "release"},
                                                headers=root["console"]).json()["items"]))
        with self.database.connection() as conn:
            conn.execute("DELETE FROM app_releases")


if __name__ == "__main__":
    unittest.main()
