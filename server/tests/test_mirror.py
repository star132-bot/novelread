"""Download mirror: URLs are handed out only for uploaded objects of the right size."""
import sys
import tempfile
import threading
import unittest
from functools import partial
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "server"), str(ROOT / "tools" / "mkbook")]

from app import mirror  # noqa: E402
from app.config import Settings  # noqa: E402


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


class DownloadMirrorTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "voices").mkdir()
        (self.root / "voices" / "zipvoice-r2-abc.zip").write_bytes(b"x" * 1234)
        self.http = HTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(self.root)))
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.http.server_port}"

    def tearDown(self):
        self.http.shutdown()

    def test_uploaded_object_of_the_right_size_is_used(self):
        downloads = mirror.DownloadMirror(self.base + "/")
        key = mirror.voice_key("zipvoice-r2-abc")
        self.assertEqual(f"{self.base}/voices/zipvoice-r2-abc.zip", downloads.url_for(key, 1234))
        # Confirmed objects are remembered: no further request once the server is gone.
        self.http.shutdown()
        self.assertEqual(f"{self.base}/voices/zipvoice-r2-abc.zip", downloads.url_for(key, 1234))

    def test_missing_or_wrong_size_object_falls_back(self):
        downloads = mirror.DownloadMirror(self.base)
        self.assertIsNone(downloads.url_for(mirror.voice_key("zipvoice-r2-abc"), 999))
        self.assertIsNone(downloads.url_for(mirror.apk_key("mkread-0.3.1-4-abc"), 1234))

    def test_missing_object_is_rechecked_later(self):
        downloads = mirror.DownloadMirror(self.base)
        key = mirror.apk_key("mkread-0.3.1-4-abc")
        self.assertIsNone(downloads.url_for(key, 5))
        (self.root / "app").mkdir()
        (self.root / "app" / "mkread-0.3.1-4-abc.apk").write_bytes(b"apk!!")
        self.assertIsNone(downloads.url_for(key, 5))
        downloads._missing_until.clear()
        self.assertEqual(f"{self.base}/app/mkread-0.3.1-4-abc.apk", downloads.url_for(key, 5))

    def test_unreachable_or_unset_mirror_is_ignored(self):
        self.assertIsNone(mirror.DownloadMirror("").url_for("voices/a.zip", 1))
        self.assertIsNone(mirror.DownloadMirror("http://127.0.0.1:9").url_for("voices/a.zip", 1))

    def test_mirror_must_be_https(self):
        with self.assertRaisesRegex(ValueError, "DOWNLOAD_MIRROR_URL"):
            Settings(database_url="postgresql://unused", download_mirror_url="http://example.com")


if __name__ == "__main__":
    unittest.main()
