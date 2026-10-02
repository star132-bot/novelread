"""MKread cloud library API.

Books are published as validated .mkbook packages. Clients poll the catalog with a
change cursor, download newer packages, and import them through the same validation
the app uses for local files.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
import zipfile
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse

import mkbook

from .auth import Authenticator, Principal
from .config import Settings
from .db import Database

API = "/api/v1"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    database = Database(settings.database_url)
    auth = Authenticator(settings, database)
    books_dir = settings.data_dir / "books"

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        books_dir.mkdir(parents=True, exist_ok=True)
        database.open()
        yield
        database.close()
        auth.close()

    app = FastAPI(title="MKread Cloud Library", version="1.0.0", lifespan=lifespan, docs_url=None, redoc_url=None)

    app.state.authenticator = auth
    app.include_router(auth.router())

    def reader(request: Request) -> Principal:
        return auth.require_reader(request)

    def admin(request: Request) -> Principal:
        return auth.require_admin(request)

    def book_json(row: dict, request: Request) -> dict:
        if row["deleted_at"] is not None:
            return {"id": row["id"], "deleted": True, "seq": row["seq"]}
        base = str(request.base_url).rstrip("/")
        return {
            "id": row["id"],
            "deleted": False,
            "seq": row["seq"],
            "revision": row["revision"],
            "title": row["title"],
            "author": row["author"],
            "status": row["status"],
            "tags": row["tags"],
            "description": row["description"],
            "chapterCount": row["chapter_count"],
            "charCount": row["char_count"],
            "coverUrl": f"{base}{API}/books/{row['id']}/cover?r={row['revision']}" if row["cover_path"] else None,
            "packageUrl": f"{base}{API}/books/{row['id']}/package?r={row['revision']}",
            "packageSha256": row["package_sha256"],
            "packageSize": row["package_size"],
            "publishedAt": row["published_at"].isoformat(),
        }

    @app.get("/healthz")
    def healthz() -> dict:
        with database.connection() as conn:
            conn.execute("SELECT 1")
        return {"ok": True}

    @app.get(f"{API}/me")
    def me(principal: Principal = Depends(reader)) -> dict:
        return {"subject": principal.subject, "name": principal.name, "isAdmin": principal.is_admin}

    @app.get(f"{API}/catalog")
    def catalog(
        request: Request,
        since: int = Query(0, ge=0, description="Return changes with seq greater than this cursor"),
        limit: int = Query(200, ge=1, le=500),
        _principal: Principal = Depends(reader),
    ) -> dict:
        with database.connection() as conn:
            rows = conn.execute(
                "SELECT * FROM books WHERE seq > %s ORDER BY seq LIMIT %s", (since, limit)
            ).fetchall()
        items = [book_json(row, request) for row in rows]
        cursor = items[-1]["seq"] if items else since
        return {"books": items, "cursor": cursor, "hasMore": len(items) == limit}

    def load_book(book_id: str) -> dict:
        if not mkbook.BOOK_ID.match(book_id):
            raise HTTPException(404, "书不存在")
        with database.connection() as conn:
            row = conn.execute("SELECT * FROM books WHERE id = %s", (book_id,)).fetchone()
        if row is None or row["deleted_at"] is not None:
            raise HTTPException(404, "书不存在")
        return row

    @app.get(f"{API}/books/{{book_id}}")
    def book(book_id: str, request: Request, _principal: Principal = Depends(reader)) -> dict:
        row = load_book(book_id)
        return {**book_json(row, request), "manifest": row["manifest"]}

    @app.get(f"{API}/books/{{book_id}}/package")
    def package(book_id: str, _principal: Principal = Depends(reader)) -> FileResponse:
        row = load_book(book_id)
        return FileResponse(
            settings.data_dir / row["package_path"],
            media_type=mkbook.MIMETYPE,
            filename=f"{book_id}-r{row['revision']}.mkbook",
            headers={"ETag": f'"{row["package_sha256"]}"', "X-MKBook-Revision": str(row["revision"])},
        )

    @app.get(f"{API}/books/{{book_id}}/cover")
    def cover(book_id: str, _principal: Principal = Depends(reader)) -> FileResponse:
        row = load_book(book_id)
        if not row["cover_path"]:
            raise HTTPException(404, "没有封面")
        path = settings.data_dir / row["cover_path"]
        media = mkbook.COVER_TYPES.get(path.suffix.lower(), "application/octet-stream")
        return FileResponse(path, media_type=media, headers={"Cache-Control": "public, max-age=86400"})

    @app.post(f"{API}/books", status_code=201)
    async def publish(request: Request, file: UploadFile, principal: Principal = Depends(admin)) -> dict:
        fd, temp_name = tempfile.mkstemp(suffix=".mkbook", dir=books_dir)
        temp = Path(temp_name)
        try:
            size = 0
            digest = hashlib.sha256()
            with os.fdopen(fd, "wb") as out:
                while chunk := await file.read(1024 * 1024):
                    size += len(chunk)
                    if size > settings.max_upload_bytes:
                        raise HTTPException(413, "文件太大")
                    digest.update(chunk)
                    out.write(chunk)
            try:
                manifest = mkbook.validate_package(temp)
            except mkbook.MkBookError as error:
                raise HTTPException(422, f"MKBook 校验失败：{error}") from None
            return store(manifest, temp, size, digest.hexdigest(), principal, request)
        finally:
            temp.unlink(missing_ok=True)

    def store(manifest: dict, temp: Path, size: int, sha: str, principal: Principal, request: Request) -> dict:
        book_id, revision = manifest["id"], manifest["revision"]
        meta = manifest["metadata"]
        book_dir = books_dir / book_id
        book_dir.mkdir(parents=True, exist_ok=True)
        package_rel = f"books/{book_id}/r{revision}.mkbook"
        cover_rel = None
        with database.connection() as conn, conn.transaction():
            current = conn.execute(
                "SELECT revision, deleted_at FROM books WHERE id = %s FOR UPDATE", (book_id,)
            ).fetchone()
            if current and current["deleted_at"] is None and revision <= current["revision"]:
                raise HTTPException(409, f"云端已有 r{current['revision']}，新版本的 revision 必须更大")
            shutil.copyfile(temp, settings.data_dir / package_rel)
            if meta.get("cover"):
                cover_rel = f"books/{book_id}/r{revision}{Path(meta['cover']).suffix}"
                with zipfile.ZipFile(temp) as zf:
                    (settings.data_dir / cover_rel).write_bytes(zf.read(meta["cover"]))
            values = dict(
                id=book_id,
                revision=revision,
                title=meta["title"],
                author=meta.get("author"),
                status=meta.get("status"),
                tags=meta.get("tags") or [],
                description=meta.get("description"),
                chapter_count=len(manifest["chapters"]),
                char_count=sum(c["chars"] for c in manifest["chapters"]),
                cover_path=cover_rel,
                manifest=json.dumps(manifest, ensure_ascii=False),
                package_path=package_rel,
                package_sha256=sha,
                package_size=size,
                published_by=principal.subject,
            )
            row = conn.execute(
                """
                INSERT INTO books (id, revision, title, author, status, tags, description, chapter_count,
                                   char_count, cover_path, manifest, package_path, package_sha256,
                                   package_size, published_by)
                VALUES (%(id)s, %(revision)s, %(title)s, %(author)s, %(status)s, %(tags)s, %(description)s,
                        %(chapter_count)s, %(char_count)s, %(cover_path)s, %(manifest)s, %(package_path)s,
                        %(package_sha256)s, %(package_size)s, %(published_by)s)
                ON CONFLICT (id) DO UPDATE SET
                    revision = EXCLUDED.revision, title = EXCLUDED.title, author = EXCLUDED.author,
                    status = EXCLUDED.status, tags = EXCLUDED.tags, description = EXCLUDED.description,
                    chapter_count = EXCLUDED.chapter_count, char_count = EXCLUDED.char_count,
                    cover_path = EXCLUDED.cover_path, manifest = EXCLUDED.manifest,
                    package_path = EXCLUDED.package_path, package_sha256 = EXCLUDED.package_sha256,
                    package_size = EXCLUDED.package_size, published_by = EXCLUDED.published_by,
                    published_at = now(), deleted_at = NULL, seq = nextval('catalog_seq')
                RETURNING *
                """,
                values,
            ).fetchone()
            conn.execute(
                """
                INSERT INTO book_revisions (book_id, revision, package_path, package_sha256, package_size, published_by)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (book_id, revision) DO UPDATE SET
                    package_path = EXCLUDED.package_path, package_sha256 = EXCLUDED.package_sha256,
                    package_size = EXCLUDED.package_size, published_by = EXCLUDED.published_by,
                    published_at = now()
                """,
                (book_id, revision, package_rel, sha, size, principal.subject),
            )
        return book_json(row, request)

    @app.delete(f"{API}/books/{{book_id}}")
    def unpublish(book_id: str, request: Request, _principal: Principal = Depends(admin)) -> dict:
        with database.connection() as conn:
            row = conn.execute(
                "UPDATE books SET deleted_at = now(), seq = nextval('catalog_seq') "
                "WHERE id = %s AND deleted_at IS NULL RETURNING *",
                (book_id,),
            ).fetchone()
        if row is None:
            raise HTTPException(404, "书不存在")
        return book_json(row, request)

    @app.get("/", response_class=HTMLResponse)
    def index() -> str:
        return (Path(__file__).parent / "admin.html").read_text(encoding="utf-8")

    return app


app = create_app() if os.environ.get("DATABASE_URL") else None
