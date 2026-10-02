"""App release publishing and the update check (needs TEST_DATABASE_URL, see test_auth_flow.py)."""
import hashlib
import os
import sys
import tempfile
import threading
import unittest
from functools import partial
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "server"), str(ROOT / "tools" / "mkbook")]

from fastapi.testclient import TestClient  # noqa: E402

from app import releases  # noqa: E402
from app.config import Settings  # noqa: E402
from app.db import Database  # noqa: E402
from app.main import create_app  # noqa: E402


@unittest.skipUnless(os.environ.get("TEST_DATABASE_URL"), "TEST_DATABASE_URL not set")
class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.source = Path(tempfile.mkdtemp())
        self.apk = b"PK\x03\x04fake-apk-body" * 100
        (self.source / "app.apk").write_bytes(self.apk)
        handler = partial(SimpleHTTPRequestHandler, directory=str(self.source))
        self.http = HTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.http.server_port}/app.apk"
        self.data_dir = Path(tempfile.mkdtemp())
        self.database = Database(os.environ["TEST_DATABASE_URL"])
        self.database.open()
        with self.database.connection() as conn:
            conn.execute("DELETE FROM app_releases")

    def tearDown(self):
        self.http.shutdown()
        self.database.close()

    def test_publish_then_clients_see_and_download_the_update(self):
        sha = hashlib.sha256(self.apk).hexdigest()
        with self.database.connection() as conn:
            releases.publish(conn, self.data_dir, self.url, 3, "0.3.0", sha, "新功能", min_supported=2)
            with self.assertRaisesRegex(releases.ReleaseError, "必须更大"):
                releases.publish(conn, self.data_dir, self.url, 3, "0.3.0", sha, "", 0)
            with self.assertRaisesRegex(releases.ReleaseError, "sha256"):
                releases.publish(conn, self.data_dir, self.url, 4, "0.4.0", "0" * 64, "", 0)

        settings = Settings(database_url=os.environ["TEST_DATABASE_URL"], data_dir=self.data_dir,
                            read_access="login", admin_subjects=[], admin_api_token="")
        with TestClient(create_app(settings)) as client:
            old = client.get("/api/v1/app/latest", params={"current": 1}).json()
            self.assertTrue(old["available"] and old["mandatory"])
            self.assertEqual(("0.3.0", 3, "新功能"), (old["versionName"], old["versionCode"], old["notes"]))
            same = client.get("/api/v1/app/latest", params={"current": 3}).json()
            self.assertFalse(same["available"])
            body = client.get(old["apkUrl"].replace("http://testserver", ""))
            self.assertEqual(200, body.status_code)
            self.assertEqual(old["apkSha256"], hashlib.sha256(body.content).hexdigest())
            self.assertIn("immutable", body.headers["cache-control"])


if __name__ == "__main__":
    unittest.main()
