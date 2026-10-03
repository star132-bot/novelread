"""Optional download mirror for APKs and voice packs (Aliyun OSS in mainland China).

Cloudflare is slow from mainland China, so large immutable downloads can also be served from an
object store there. The store holds the same files under the same immutable names
(`app/<name>.apk`, `voices/<name>.zip`); scripts/sync-download-mirror.sh uploads them. A mirror URL
is handed out only after a HEAD request confirms the object is there with the right size, so a
missing upload falls back to the Cloudflare URL. Clients verify size and SHA-256 either way.

    python -m app.mirror plan    # "<path under DATA_DIR>\t<object key>\t<size>" for every live file
"""
from __future__ import annotations

import os
import sys
import threading
import time
import urllib.error
import urllib.request

MISSING_RECHECK_SECONDS = 300
HEAD_TIMEOUT_SECONDS = 3


def apk_key(file_name: str) -> str:
    return f"app/{file_name}.apk"


def voice_key(file_name: str) -> str:
    return f"voices/{file_name}.zip"


class DownloadMirror:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self._lock = threading.Lock()
        self._present: set[str] = set()
        self._missing_until: dict[str, float] = {}

    def url_for(self, key: str, size: int) -> str | None:
        """The mirror URL for [key] if the object is uploaded with [size] bytes, else None."""
        if not self.base_url:
            return None
        url = f"{self.base_url}/{key}"
        with self._lock:
            if url in self._present:
                return url
            if self._missing_until.get(url, 0) > time.monotonic():
                return None
        found = self._has(url, size)
        with self._lock:
            # Objects are immutable, so a confirmed upload stays valid; a miss is retried later.
            if found:
                self._present.add(url)
            else:
                self._missing_until[url] = time.monotonic() + MISSING_RECHECK_SECONDS
        return url if found else None

    @staticmethod
    def _has(url: str, size: int) -> bool:
        request = urllib.request.Request(url, method="HEAD")
        try:
            with urllib.request.urlopen(request, timeout=HEAD_TIMEOUT_SECONDS) as response:
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
