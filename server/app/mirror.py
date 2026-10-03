"""Optional download mirror for APKs and voice packs that is fast from mainland China.

Cloudflare is slow from mainland China, so large immutable downloads can also be served from a
mirror holding the same files under the same flat, immutable names (`<name>.apk`, `<name>.zip`):
a GitHub release fetched through a mainland proxy (scripts/sync-github-mirror.sh) or an object
store such as Aliyun OSS (server/deploy/sync-download-mirror.sh). A mirror URL is handed out only
while a recent HEAD request found the file with the right size, so a missing upload or a mirror
outage falls back to the Cloudflare URL. Clients verify size and SHA-256 either way.

    python -m app.mirror plan    # "<path under DATA_DIR>\t<file name>\t<size>" for every live file
"""
from __future__ import annotations

import os
import sys
import threading
import time
import urllib.error
import urllib.request

# Third-party mirrors can disappear, so answers are re-checked rather than trusted forever.
RECHECK_SECONDS = 600
HEAD_TIMEOUT_SECONDS = 3


def apk_key(file_name: str) -> str:
    return f"{file_name}.apk"


def voice_key(file_name: str) -> str:
    return f"{file_name}.zip"


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


# The app does not follow redirects, so a mirror that redirects counts as missing.
_opener = urllib.request.build_opener(_NoRedirects)


class DownloadMirror:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self._lock = threading.Lock()
        # url -> (found, checked at)
        self._checked: dict[str, tuple[bool, float]] = {}

    def url_for(self, key: str, size: int) -> str | None:
        """The mirror URL for [key] if the object is uploaded with [size] bytes, else None."""
        if not self.base_url:
            return None
        url = f"{self.base_url}/{key}"
        with self._lock:
            cached = self._checked.get(url)
        if cached is None or time.monotonic() - cached[1] > RECHECK_SECONDS:
            cached = (self._has(url, size), time.monotonic())
            with self._lock:
                self._checked[url] = cached
        return url if cached[0] else None

    @staticmethod
    def _has(url: str, size: int) -> bool:
        request = urllib.request.Request(url, method="HEAD")
        try:
            with _opener.open(request, timeout=HEAD_TIMEOUT_SECONDS) as response:
                return response.status == 200 and response.headers.get("Content-Length") == str(size)
        except (urllib.error.URLError, OSError, ValueError):
            return False


def plan(conn) -> list[tuple[str, str, int]]:
    """Every file clients can currently download: (path under DATA_DIR, object key, size)."""
    from . import releases, voices

    rows = [
        (row["package_path"], voice_key(voices.file_name(row)), row["package_size"])
        for row in conn.execute("SELECT * FROM voice_packs ORDER BY id").fetchall()
    ]
    rows += [
        (row["apk_path"], apk_key(releases.file_name(row)), row["apk_size"])
        for row in conn.execute(
            "SELECT * FROM app_releases WHERE withdrawn_at IS NULL ORDER BY version_code DESC"
        ).fetchall()
    ]
    return rows


def main(argv: list[str]) -> int:
    if argv != ["plan"]:
        print(__doc__, file=sys.stderr)
        return 2
    from .db import Database

    database = Database(os.environ["DATABASE_URL"])
    database.open()
    try:
        with database.connection() as conn:
            for path, key, size in plan(conn):
                print(f"{path}\t{key}\t{size}")
    finally:
        database.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
