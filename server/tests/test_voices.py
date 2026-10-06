"""Voice pack validation and publishing (needs TEST_DATABASE_URL, see test_auth_flow.py)."""
import hashlib
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "server"), str(ROOT / "tools" / "mkbook")]

from fastapi.testclient import TestClient  # noqa: E402

from app import voices  # noqa: E402
from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402


def make_pack(directory: Path, *, revision=1, tamper=False, extra=False) -> Path:
    files = {"models/melo-zh-en/model.onnx": b"weights", "models/melo-zh-en/tokens.txt": b"a 1\n"}
    manifest = {
        "schemaVersion": 1,
        "id": "melo-zh-en",
        "revision": revision,
        "files": [{"path": p, "size": len(d), "sha256": hashlib.sha256(d).hexdigest()} for p, d in files.items()],
    }
    path = directory / f"melo-r{revision}.zip"
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("pack.json", json.dumps(manifest))
        for name, data in files.items():
            zf.writestr(name, data + (b"!" if tamper and name.endswith(".onnx") else b""))
        if extra:
            zf.writestr("models/melo-zh-en/evil.sh", b"x")
    return path


class ValidateTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def test_valid_pack(self):
        manifest = voices.validate_pack(make_pack(self.dir))
        self.assertEqual(("melo-zh-en", 1, 2), (manifest["id"], manifest["revision"], len(manifest["files"])))

    def test_tampered_file_is_rejected(self):
        with self.assertRaisesRegex(voices.VoicePackError, "大小不符|sha256"):
            voices.validate_pack(make_pack(self.dir, tamper=True))

    def test_undeclared_file_is_rejected(self):
        with self.assertRaisesRegex(voices.VoicePackError, "未声明"):
            voices.validate_pack(make_pack(self.dir, extra=True))


@unittest.skipUnless(os.environ.get("TEST_DATABASE_URL"), "TEST_DATABASE_URL not set")
class PublishTest(unittest.TestCase):
    def test_published_pack_is_listed_and_downloadable_without_login(self):
        data_dir = Path(tempfile.mkdtemp())
        settings = Settings(database_url=os.environ["TEST_DATABASE_URL"], data_dir=data_dir, read_access="login",
                            admin_subjects=[], admin_api_token="")
        app = create_app(settings)
        with TestClient(app) as client:
            from app.db import Database
            database = Database(os.environ["TEST_DATABASE_URL"])
            database.open()
            try:
                with database.connection() as conn:
                    conn.execute("DELETE FROM voice_packs WHERE id = 'melo-zh-en'")
                    voices.publish(conn, data_dir, make_pack(data_dir, revision=2))
                    with self.assertRaisesRegex(voices.VoicePackError, "更旧"):
                        voices.publish(conn, data_dir, make_pack(data_dir, revision=1))
            finally:
                database.close()
            listing = client.get("/api/v1/voices").json()["voices"]
            pack = next(v for v in listing if v["id"] == "melo-zh-en")
            self.assertEqual(2, pack["revision"])
            response = client.get(pack["packageUrl"].replace("http://testserver", ""))
            self.assertEqual(200, response.status_code)
            self.assertEqual(pack["packageSha256"], hashlib.sha256(response.content).hexdigest())
            self.assertIn("immutable", response.headers["cache-control"])


if __name__ == "__main__":
    unittest.main()
