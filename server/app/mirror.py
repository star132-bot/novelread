"""Optional download mirror for APKs and voice packs that is fast from mainland China.

Cloudflare is slow from mainland China, so large immutable downloads can also be served from a
mirror holding the same files under the same flat, immutable names (`<name>.apk`, `<name>.zip`):
a GitHub release fetched through a mainland proxy (scripts/sync-github-mirror.sh) or an object
store such as Aliyun OSS (server/deploy/sync-download-mirror.sh). A mirror URL is handed out only
while a recent HEAD request found the file with the right size, so a missing upload or a mirror
outage falls back to the Cloudflare URL. Several mirrors (comma separated) are tried in order,
because free proxies come and go. Clients verify size and SHA-256 either way.

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
# A file usually reaches the mirror a minute or two after it is published, so a miss is
# re-checked soon instead of pinning clients to the slow Cloudflare URL for RECHECK_SECONDS.
MISS_RECHECK_SECONDS = 30
HEAD_TIMEOUT_SECONDS = 3
# A mirror that cannot be reached at all is skipped for this long, so one dead proxy does not
# add a HEAD timeout per file to every catalog request.
UNREACHABLE_SKIP_SECONDS = 120


def apk_key(file_name: str) -> str:
    return f"{file_name}.apk"


def voice_key(file_name: str) -> str:
    return f"{file_name}.zip"


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


# The app does not follow redirects, so a mirror that redirects counts as missing.
_opener = urllib.request.build_opener(_NoRedirects)


def base_urls(value: str) -> list[str]:
    """Mirror base URLs from a comma-separated setting, in order of preference."""
    return [part.strip().rstrip("/") for part in value.split(",") if part.strip()]


class DownloadMirror:
    def __init__(self, base_url: str) -> None:
        self.base_urls = base_urls(base_url)
        self._lock = threading.Lock()
        # url -> (found, checked at)
        self._checked: dict[str, tuple[bool, float]] = {}
        # base url -> monotonic time until which it is skipped
        self._unreachable: dict[str, float] = {}

    def url_for(self, key: str, size: int) -> str | None:
        """The first mirror URL for [key] whose object has [size] bytes, else None."""
        for base in self.base_urls:
            with self._lock:
                if self._unreachable.get(base, 0.0) > time.monotonic():
                    continue
            url = f"{base}/{key}"
            if self._found(base, url, size):
                return url
        return None

    def _found(self, base: str, url: str, size: int) -> bool:
        with self._lock:
            cached = self._checked.get(url)
        max_age = RECHECK_SECONDS if cached and cached[0] else MISS_RECHECK_SECONDS
        if cached is None or time.monotonic() - cached[1] > max_age:
            found = self._has(url, size)
            cached = (bool(found), time.monotonic())
            with self._lock:
                self._checked[url] = cached
                if found is None:
                    self._unreachable[base] = time.monotonic() + UNREACHABLE_SKIP_SECONDS
        return cached[0]

    @staticmethod
    def _has(url: str, size: int) -> bool | None:
        """Whether the object exists with [size] bytes; None when the mirror did not answer."""
        request = urllib.request.Request(url, method="HEAD")
        try:
            with _opener.open(request, timeout=HEAD_TIMEOUT_SECONDS) as response:
                return response.status == 200 and response.headers.get("Content-Length") == str(size)
        except urllib.error.HTTPError:
            return False
        except (urllib.error.URLError, OSError, ValueError):
            return None


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
