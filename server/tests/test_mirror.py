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

    def do_HEAD(self):
        if self.path == "/moved.zip":
            self.send_response(302)
            self.send_header("Location", "/zipvoice-r2-abc.zip")
            self.end_headers()
            return
        super().do_HEAD()


class DownloadMirrorTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "zipvoice-r2-abc.zip").write_bytes(b"x" * 1234)
        self.http = HTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(self.root)))
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.http.server_port}"

    def tearDown(self):
        self.http.shutdown()

    def test_uploaded_object_of_the_right_size_is_used(self):
        downloads = mirror.DownloadMirror(self.base + "/")
        key = mirror.voice_key("zipvoice-r2-abc")
        self.assertEqual(f"{self.base}/zipvoice-r2-abc.zip", downloads.url_for(key, 1234))

    def test_answers_are_cached_then_rechecked(self):
        downloads = mirror.DownloadMirror(self.base)
        key = mirror.voice_key("zipvoice-r2-abc")
        self.assertIsNotNone(downloads.url_for(key, 1234))
        # Within the recheck window no request is made, so the outage is not noticed yet.
        self.http.shutdown()
        self.http.server_close()
        self.assertIsNotNone(downloads.url_for(key, 1234))
        # After it, the unreachable mirror is dropped and clients get the origin URL again.
        downloads._checked = {url: (found, at - mirror.RECHECK_SECONDS - 1) for url, (found, at) in downloads._checked.items()}
        self.assertIsNone(downloads.url_for(key, 1234))

    def test_missing_or_wrong_size_object_falls_back(self):
        downloads = mirror.DownloadMirror(self.base)
        self.assertIsNone(downloads.url_for(mirror.voice_key("zipvoice-r2-abc"), 999))
        self.assertIsNone(downloads.url_for(mirror.apk_key("mkread-0.3.1-4-abc"), 1234))

    def test_missing_object_is_rechecked_later(self):
        downloads = mirror.DownloadMirror(self.base)
        key = mirror.apk_key("mkread-0.3.1-4-abc")
        self.assertIsNone(downloads.url_for(key, 5))
        (self.root / "mkread-0.3.1-4-abc.apk").write_bytes(b"apk!!")
        self.assertIsNone(downloads.url_for(key, 5))
        # A miss is only trusted for MISS_RECHECK_SECONDS, not the full RECHECK_SECONDS.
        downloads._checked = {
            url: (found, at - mirror.MISS_RECHECK_SECONDS - 1) for url, (found, at) in downloads._checked.items()
        }
        self.assertEqual(f"{self.base}/mkread-0.3.1-4-abc.apk", downloads.url_for(key, 5))

    def test_redirecting_mirror_is_ignored_because_the_app_does_not_follow_redirects(self):
        # Following the redirect would find a file of the right size.
        self.assertIsNone(mirror.DownloadMirror(self.base).url_for("moved.zip", 1234))

    def test_unreachable_or_unset_mirror_is_ignored(self):
        self.assertIsNone(mirror.DownloadMirror("").url_for("voices/a.zip", 1))
        self.assertIsNone(mirror.DownloadMirror("http://127.0.0.1:9").url_for("voices/a.zip", 1))

    def test_first_mirror_that_has_the_file_is_used(self):
        empty = Path(tempfile.mkdtemp())
        other = HTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(empty)))
        threading.Thread(target=other.serve_forever, daemon=True).start()
        self.addCleanup(other.shutdown)
        missing = f"http://127.0.0.1:{other.server_port}"
        downloads = mirror.DownloadMirror(f"{missing}, {self.base}/")
        self.assertEqual(f"{self.base}/zipvoice-r2-abc.zip", downloads.url_for("zipvoice-r2-abc.zip", 1234))

    def test_unreachable_mirror_is_skipped_for_a_while(self):
        dead = "http://127.0.0.1:9"
        downloads = mirror.DownloadMirror(f"{dead},{self.base}")
        self.assertEqual(f"{self.base}/zipvoice-r2-abc.zip", downloads.url_for("zipvoice-r2-abc.zip", 1234))
        downloads._checked.clear()
        downloads._has = lambda url, size: self.fail(f"{url} should be skipped") if url.startswith(dead) else True
        self.assertEqual(f"{self.base}/zipvoice-r2-abc.zip", downloads.url_for("zipvoice-r2-abc.zip", 1234))

    def test_mirror_must_be_https(self):
        with self.assertRaisesRegex(ValueError, "DOWNLOAD_MIRROR_URL"):
            Settings(database_url="postgresql://unused", download_mirror_url="http://example.com")
        with self.assertRaisesRegex(ValueError, "DOWNLOAD_MIRROR_URL"):
            Settings(database_url="postgresql://unused", download_mirror_url="https://a.example, http://b.example")


if __name__ == "__main__":
    unittest.main()
