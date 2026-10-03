"""App releases for in-app updates.

Publishing (on the app server; the APK is fetched from a URL so it never has to be pushed
across a slow link):

    docker compose exec api python -m app.releases publish \\
        --url https://ghfast.top/https://github.com/<owner>/<repo>/releases/download/v0.3.0/mkread-0.3.0.apk \\
        --version-code 3 --version-name 0.3.0 --sha256 <hex> --notes "更新内容" [--min-supported 2]

Clients call GET /api/v1/app/latest; no sign-in is needed so every install can update.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys
from pathlib import Path

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse

from .mirror import DownloadMirror, apk_key

SHA256 = re.compile(r"^[0-9a-f]{64}$")
VERSION_NAME = re.compile(r"^[0-9A-Za-z.+-]{1,32}$")
FILE_NAME = re.compile(r"^mkread-([0-9A-Za-z.+-]{1,32})-(\d{1,9})-([0-9a-f]{16})$")
MAX_APK_BYTES = 1024 * 1024 * 1024

SCHEMA = """
CREATE TABLE IF NOT EXISTS app_releases (
    version_code   INTEGER PRIMARY KEY,
    version_name   TEXT NOT NULL,
    apk_path       TEXT NOT NULL,
    apk_size       BIGINT NOT NULL,
    apk_sha256     TEXT NOT NULL,
    min_supported  INTEGER NOT NULL DEFAULT 0,
    notes          TEXT NOT NULL DEFAULT '',
    published_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    withdrawn_at   TIMESTAMPTZ
);
"""


class ReleaseError(Exception):
    pass


def file_name(row: dict) -> str:
    return f"mkread-{row['version_name']}-{row['version_code']}-{row['apk_sha256'][:16]}"


def router(database, data_dir: Path, mirror: DownloadMirror | None = None) -> APIRouter:
    api = APIRouter(prefix="/api/v1/app")

    @api.get("/latest")
    def latest(request: Request, current: int = 0) -> dict:
        with database.connection() as conn:
            row = conn.execute(
                "SELECT * FROM app_releases WHERE withdrawn_at IS NULL ORDER BY version_code DESC LIMIT 1"
            ).fetchone()
        if row is None:
            return {"available": False}
        base = str(request.base_url).rstrip("/")
        mirrored = mirror.url_for(apk_key(file_name(row)), row["apk_size"]) if mirror else None
        return {
            "available": row["version_code"] > current,
            "versionCode": row["version_code"],
            "versionName": row["version_name"],
            "notes": row["notes"],
            # Immutable .apk path: Cloudflare caches it and a URL never changes content.
            "apkUrl": mirrored or f"{base}/api/v1/app/files/{file_name(row)}.apk",
            "apkSize": row["apk_size"],
            "apkSha256": row["apk_sha256"],
            "mandatory": current < row["min_supported"],
        }

    @api.get("/files/{name}.apk")
    def apk(name: str) -> FileResponse:
        match = FILE_NAME.match(name)
        if not match:
            raise HTTPException(404, "安装包不存在")
        with database.connection() as conn:
            row = conn.execute(
                "SELECT * FROM app_releases WHERE version_code = %s AND withdrawn_at IS NULL",
                (int(match.group(2)),),
            ).fetchone()
        if row is None or file_name(row) != name:
            raise HTTPException(404, "安装包不存在或已撤回")
        return FileResponse(
            data_dir / row["apk_path"],
            media_type="application/vnd.android.package-archive",
            filename=f"mkread-{row['version_name']}.apk",
            headers={"Cache-Control": "public, max-age=31536000, immutable", "ETag": f'"{row["apk_sha256"]}"'},
        )

    return api


def publish(conn, data_dir: Path, url: str, version_code: int, version_name: str, sha256: str,
            notes: str, min_supported: int) -> dict:
    if not VERSION_NAME.match(version_name) or version_code < 1 or not SHA256.match(sha256):
        raise ReleaseError("版本号或 sha256 不合规")
    current = conn.execute("SELECT max(version_code) AS v FROM app_releases").fetchone()["v"]
    if current is not None and version_code <= current:
        raise ReleaseError(f"已有 versionCode {current}，新版本必须更大")
    relative = f"releases/mkread-{version_name}-{version_code}.apk"
    target = data_dir / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(".apk.partial")
    digest, size = hashlib.sha256(), 0
    with httpx.stream("GET", url, follow_redirects=True, timeout=httpx.Timeout(30, read=120)) as response:
        response.raise_for_status()
        with partial.open("wb") as out:
            for chunk in response.iter_bytes(1 << 20):
                size += len(chunk)
                if size > MAX_APK_BYTES:
                    raise ReleaseError("安装包超过 1GB")
                digest.update(chunk)
                out.write(chunk)
    if digest.hexdigest() != sha256:
        partial.unlink(missing_ok=True)
        raise ReleaseError("下载的安装包 sha256 不匹配")
    if partial.read_bytes()[:2] != b"PK":
        partial.unlink(missing_ok=True)
        raise ReleaseError("不是有效的 APK")
    partial.replace(target)
    return conn.execute(
        """
        INSERT INTO app_releases (version_code, version_name, apk_path, apk_size, apk_sha256, min_supported, notes)
        VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING *
        """,
        (version_code, version_name, relative, size, sha256, min_supported, notes),
    ).fetchone()


def main(argv: list[str]) -> int:
    from .db import Database

    parser = argparse.ArgumentParser(prog="python -m app.releases")
    sub = parser.add_subparsers(dest="command", required=True)
    pub = sub.add_parser("publish")
    pub.add_argument("--url", required=True)
    pub.add_argument("--version-code", type=int, required=True)
    pub.add_argument("--version-name", required=True)
    pub.add_argument("--sha256", required=True)
    pub.add_argument("--notes", default="")
    pub.add_argument("--min-supported", type=int, default=0)
    withdraw = sub.add_parser("withdraw")
    withdraw.add_argument("--version-code", type=int, required=True)
    args = parser.parse_args(argv)

    database = Database(os.environ["DATABASE_URL"])
    database.open()
    try:
        with database.connection() as conn:
            if args.command == "publish":
                row = publish(conn, Path(os.environ.get("DATA_DIR", "/data")), args.url, args.version_code,
                              args.version_name, args.sha256.lower(), args.notes, args.min_supported)
                print(f"published {row['version_name']} ({row['version_code']}), {row['apk_size'] // 1048576} MB")
            else:
                conn.execute("UPDATE app_releases SET withdrawn_at = now() WHERE version_code = %s",
                             (args.version_code,))
                print(f"withdrew {args.version_code}")
    except (ReleaseError, httpx.HTTPError) as error:
        print(f"错误：{error}", file=sys.stderr)
        return 1
    finally:
        database.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
