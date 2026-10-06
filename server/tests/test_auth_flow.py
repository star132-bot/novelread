"""End-to-end sign-in flow against a fake MKauth. Needs a disposable PostgreSQL:

    docker compose -f server/docker-compose.dev.yml up -d db
    TEST_DATABASE_URL=postgresql://mkread:mkread-dev@127.0.0.1:55432/mkread python3 -m unittest discover server/tests
"""
import base64
import hashlib
import os
import sys
import tempfile
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import httpx

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "server"), str(ROOT / "tools" / "mkbook")]

from fastapi.testclient import TestClient  # noqa: E402

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402

ISSUER = "https://auth.example.test"


def fake_mkauth(request: httpx.Request) -> httpx.Response:
    if request.url.path == "/.well-known/openid-configuration":
        return httpx.Response(200, json={
            "authorization_endpoint": f"{ISSUER}/oauth2/authorize",
            "token_endpoint": f"{ISSUER}/oauth2/token",
            "userinfo_endpoint": f"{ISSUER}/userinfo",
        })
    if request.url.path == "/oauth2/token":
        form = parse_qs(request.content.decode())
        assert request.headers["authorization"].startswith("Basic ")
        assert form["code"] == ["mk-code"] and form["code_verifier"][0]
        return httpx.Response(200, json={"access_token": "mk-access", "token_type": "Bearer"})
    if request.url.path == "/userinfo":
        assert request.headers["authorization"] == "Bearer mk-access"
        return httpx.Response(200, json={"sub": "user-42", "name": "林默", "roles": ["mkread:admin"]})
    return httpx.Response(404)


@unittest.skipUnless(os.environ.get("TEST_DATABASE_URL"), "TEST_DATABASE_URL not set")
class AuthFlowTest(unittest.TestCase):
    def setUp(self):
        settings = Settings(
            database_url=os.environ["TEST_DATABASE_URL"],
            data_dir=Path(tempfile.mkdtemp()),
            public_base_url="https://books.example.test",
            oidc_issuer=ISSUER,
            oidc_client_id="mkread-server",
            oidc_client_secret="s3cret",
            read_access="login",
            admin_subjects=[],
            admin_api_token="",
        )
        self.app = create_app(settings)
        self.client = TestClient(self.app, follow_redirects=False).__enter__()
        self.authenticator = self.app.state.authenticator
        self.authenticator._http = httpx.Client(transport=httpx.MockTransport(fake_mkauth))

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def test_full_sign_in_issues_a_device_session(self):
        verifier = "v" * 64
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        start = self.client.get("/api/v1/auth/start", params={"port": 45678, "state": "app-state-123456", "code_challenge": challenge})
        self.assertEqual(302, start.status_code)
        authorize = urlparse(start.headers["location"])
        query = parse_qs(authorize.query)
        self.assertEqual("/oauth2/authorize", authorize.path)
        self.assertEqual(["https://books.example.test/api/v1/auth/callback"], query["redirect_uri"])
        self.assertEqual(["S256"], query["code_challenge_method"])

        callback = self.client.get("/api/v1/auth/callback", params={"state": query["state"][0], "code": "mk-code"})
        self.assertEqual(302, callback.status_code)
        back = urlparse(callback.headers["location"])
        self.assertEqual(("127.0.0.1:45678", "/callback"), (back.netloc, back.path))
        back_query = parse_qs(back.query)
        self.assertEqual(["app-state-123456"], back_query["state"])

        wrong = self.client.post("/api/v1/auth/token", json={"code": back_query["code"][0], "code_verifier": "x" * 64})
        self.assertEqual(400, wrong.status_code, "a stolen code is useless without the app's verifier")

        # The wrong attempt consumed nothing useful: codes are single-use, so sign in again.
        start = self.client.get("/api/v1/auth/start", params={"port": 45678, "state": "app-state-123456", "code_challenge": challenge})
        state = parse_qs(urlparse(start.headers["location"]).query)["state"][0]
        code = parse_qs(urlparse(self.client.get("/api/v1/auth/callback", params={"state": state, "code": "mk-code"}).headers["location"]).query)["code"][0]
        token = self.client.post("/api/v1/auth/token", json={"code": code, "code_verifier": verifier})
        self.assertEqual(200, token.status_code, token.text)
        session = token.json()
        self.assertEqual(("user-42", "林默", True), (session["subject"], session["name"], session["isAdmin"]))

        replay = self.client.post("/api/v1/auth/token", json={"code": code, "code_verifier": verifier})
        self.assertEqual(400, replay.status_code)

        me = self.client.get("/api/v1/me", headers={"Authorization": f"Bearer {session['token']}"})
        self.assertEqual({"subject": "user-42", "name": "林默", "isAdmin": True}, me.json())

        self.client.post("/api/v1/auth/logout", headers={"Authorization": f"Bearer {session['token']}"})
        after = self.client.get("/api/v1/me", headers={"Authorization": f"Bearer {session['token']}"})
        self.assertEqual(401, after.status_code)

    def test_unknown_state_is_rejected(self):
        self.assertEqual(400, self.client.get("/api/v1/auth/callback", params={"state": "nope", "code": "c"}).status_code)

    def test_catalog_requires_login(self):
        self.assertEqual(401, self.client.get("/api/v1/catalog").status_code)


if __name__ == "__main__":
    unittest.main()
