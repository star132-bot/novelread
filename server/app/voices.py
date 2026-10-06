"""Downloadable narration voice packs.

A pack is a ZIP holding pack.json ({schemaVersion, id, revision, files: [{path, size, sha256}]})
plus every listed file at its install path (models/<dir>/... or voices/...). Packs are built by
scripts/build-voice-packs.py and published with:

    docker compose exec api python -m app.voices publish /data/incoming/<pack>.zip

Voice packs are open-source models, so listing and downloading them needs no sign-in.
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse

from .mirror import DownloadMirror, voice_key

PACK_ID = re.compile(r"^[a-z0-9][a-z0-9-]{1,40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
PACKAGE_NAME = re.compile(r"^([a-z0-9][a-z0-9-]{1,40})-r(\d+)-([0-9a-f]{16})$")
MAX_FILES = 5_000
MAX_UNPACKED_BYTES = 2 * 1024 * 1024 * 1024

SCHEMA = """
CREATE TABLE IF NOT EXISTS voice_packs (
    id            TEXT PRIMARY KEY,
    revision      INTEGER NOT NULL,
    package_path  TEXT NOT NULL,
    package_size  BIGINT NOT NULL,
    package_sha256 TEXT NOT NULL,
    file_count    INTEGER NOT NULL,
    unpacked_size BIGINT NOT NULL,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
"""


class VoicePackError(Exception):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_pack(path: Path) -> dict:
    """Checks the manifest, every entry's size and SHA-256, and that nothing else is in the ZIP."""
    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        raise VoicePackError("不是有效的 ZIP") from None
    with zf:
        try:
            manifest = json.loads(zf.read("pack.json"))
        except (KeyError, json.JSONDecodeError):
            raise VoicePackError("缺少或无法解析 pack.json") from None
        if manifest.get("schemaVersion") != 1:
            raise VoicePackError("不支持的 schemaVersion")
        pack_id, revision, files = manifest.get("id"), manifest.get("revision"), manifest.get("files")
        if not isinstance(pack_id, str) or not PACK_ID.match(pack_id):
            raise VoicePackError("id 不合规")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
            raise VoicePackError("revision 必须是正整数")
        if not isinstance(files, list) or not 1 <= len(files) <= MAX_FILES:
            raise VoicePackError("files 数量不合规")
        listed, total = set(), 0
        for entry in files:
            name, size, sha = entry.get("path"), entry.get("size"), entry.get("sha256")
            if (
                not isinstance(name, str)
                or not (name.startswith("models/") or name.startswith("voices/"))
                or "\\" in name
                or any(part in {"", ".", ".."} for part in name.split("/"))
                or name in listed
            ):
                raise VoicePackError(f"非法或重复路径：{name}")
            if not isinstance(size, int) or size < 0 or not isinstance(sha, str) or not SHA256.match(sha):
                raise VoicePackError(f"{name}: size/sha256 不合规")
            info = zf.getinfo(name) if name in zf.namelist() else None
            if info is None or info.file_size != size:
                raise VoicePackError(f"{name}: 缺失或大小不符")
            digest = hashlib.sha256()
            with zf.open(info) as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    digest.update(chunk)
            if digest.hexdigest() != sha:
                raise VoicePackError(f"{name}: sha256 不匹配")
            listed.add(name)
            total += size
        if total > MAX_UNPACKED_BYTES:
            raise VoicePackError("解压后超过 2GB")
        extra = set(zf.namelist()) - listed - {"pack.json"}
        if extra:
            raise VoicePackError(f"包含未声明的文件：{sorted(extra)[:5]}")
    manifest["_unpacked_size"] = total
    return manifest


def publish(conn, data_dir: Path, source: Path) -> dict:
    manifest = validate_pack(source)
    pack_id, revision = manifest["id"], manifest["revision"]
    current = conn.execute("SELECT revision FROM voice_packs WHERE id = %s", (pack_id,)).fetchone()
    if current and revision < current["revision"]:
        raise VoicePackError(f"云端已有 r{current['revision']}，不能发布更旧的 r{revision}")
    relative = f"voices/{pack_id}-r{revision}.zip"
    target = data_dir / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(".zip.partial")
    shutil.copyfile(source, partial)
    partial.replace(target)
    row = conn.execute(
        """
        INSERT INTO voice_packs (id, revision, package_path, package_size, package_sha256, file_count, unpacked_size)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (id) DO UPDATE SET
            revision = EXCLUDED.revision, package_path = EXCLUDED.package_path,
            package_size = EXCLUDED.package_size, package_sha256 = EXCLUDED.package_sha256,
            file_count = EXCLUDED.file_count, unpacked_size = EXCLUDED.unpacked_size, published_at = now()
        RETURNING *
        """,
        (pack_id, revision, relative, target.stat().st_size, _sha256(target), len(manifest["files"]),
         manifest["_unpacked_size"]),
    ).fetchone()
    return row


def file_name(row: dict) -> str:
    return f"{row['id']}-r{row['revision']}-{row['package_sha256'][:16]}"


def router(database, data_dir: Path, mirror: DownloadMirror | None = None) -> APIRouter:
    api = APIRouter(prefix="/api/v1/voices")

    def as_json(row: dict, request: Request) -> dict:
        base = str(request.base_url).rstrip("/")
        mirrored = mirror.url_for(voice_key(file_name(row)), row["package_size"]) if mirror else None
        return {
            "id": row["id"],
            "revision": row["revision"],
            # A .zip path with revision and hash: Cloudflare caches it, and it never changes.
            "packageUrl": mirrored or f"{base}/api/v1/voices/files/{file_name(row)}.zip",
            "packageSize": row["package_size"],
            "packageSha256": row["package_sha256"],
            "unpackedSize": row["unpacked_size"],
        }

    @api.get("")
    def list_packs(request: Request) -> dict:
        with database.connection() as conn:
            rows = conn.execute("SELECT * FROM voice_packs ORDER BY id").fetchall()
        return {"voices": [as_json(row, request) for row in rows]}

    @api.get("/files/{name}.zip")
    def package(name: str) -> FileResponse:
        match = PACKAGE_NAME.match(name)
        if not match:
            raise HTTPException(404, "音色包不存在")
        pack_id, revision, sha_prefix = match.group(1), int(match.group(2)), match.group(3)
        with database.connection() as conn:
            row = conn.execute("SELECT * FROM voice_packs WHERE id = %s", (pack_id,)).fetchone()
        if row is None or row["revision"] != revision or not row["package_sha256"].startswith(sha_prefix):
            raise HTTPException(404, "音色包不存在或已更新")
        return FileResponse(
            data_dir / row["package_path"],
            media_type="application/zip",
            filename=f"{pack_id}-r{row['revision']}.zip",
            # URLs carry revision and hash, so the body never changes for a given URL.
            headers={"Cache-Control": "public, max-age=31536000, immutable", "ETag": f'"{row["package_sha256"]}"'},
        )

    return api


def main(argv: list[str]) -> int:
    """CLI: python -m app.voices publish <zip>..."""
    import os

    from .db import Database

    if len(argv) < 2 or argv[0] != "publish":
        print("usage: python -m app.voices publish <pack.zip>...", file=sys.stderr)
        return 2
    database = Database(os.environ["DATABASE_URL"])
    database.open()
    data_dir = Path(os.environ.get("DATA_DIR", "/data"))
    status = 0
    try:
        for name in argv[1:]:
            try:
                with database.connection() as conn:
                    row = publish(conn, data_dir, Path(name))
                print(f"published {row['id']} r{row['revision']} ({row['package_size'] // 1048576} MB)")
            except VoicePackError as error:
                print(f"{name}: {error}", file=sys.stderr)
                status = 1
    finally:
        database.close()
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
