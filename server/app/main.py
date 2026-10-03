"""MKread cloud library API.

Books are published as validated .mkbook packages. Clients poll the catalog with a
change cursor, download newer packages, and import them through the same validation
the app uses for local files. The admin console lives at /admin and talks to /api/v1/admin.
"""
from __future__ import annotations

import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, RedirectResponse

import mkbook

from . import admin_api, releases, voices
from .accounts import role_at_least
from .auth import Authenticator, Principal
from .books import API, BookStore, book_json
from .config import Settings
from .db import Database
from .mirror import DownloadMirror

SCRIPT_REASON = "（接口调用未填写原因）"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    database = Database(settings.database_url)
    auth = Authenticator(settings, database)
    books = BookStore(database, settings.data_dir, settings.max_upload_bytes)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        books.books_dir.mkdir(parents=True, exist_ok=True)
        database.open()
        yield
        database.close()
        auth.close()

    # The OpenAPI schema is served to signed-in admins only (see admin_api).
    app = FastAPI(title="MKread Cloud Library", version="1.1.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    app.state.authenticator = auth
    app.include_router(auth.router())
    mirror = DownloadMirror(settings.download_mirror_url)
    app.include_router(voices.router(database, settings.data_dir, mirror))
    app.include_router(releases.router(database, settings.data_dir, mirror))
    admin_api.install(app, settings, database, auth, books)

    def reader(request: Request) -> Principal:
        return auth.require_reader(request)

    def admin(request: Request) -> Principal:
        return auth.require_admin(request)

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

    @app.get(f"{API}/books/{{book_id}}")
    def book(book_id: str, request: Request, _principal: Principal = Depends(reader)) -> dict:
        row = books.load(book_id)
        return {**book_json(row, request), "manifest": row["manifest"]}

    @app.get(f"{API}/books/{{book_id}}/package")
    def package(book_id: str, _principal: Principal = Depends(reader)) -> FileResponse:
        row = books.load(book_id)
        return FileResponse(
            settings.data_dir / row["package_path"],
            media_type=mkbook.MIMETYPE,
            filename=f"{book_id}-r{row['revision']}.mkbook",
            headers={"ETag": f'"{row["package_sha256"]}"', "X-MKBook-Revision": str(row["revision"])},
        )

    @app.get(f"{API}/books/{{book_id}}/cover")
    def cover(book_id: str, principal: Principal = Depends(reader)) -> FileResponse:
        if not mkbook.BOOK_ID.match(book_id):
            raise HTTPException(404, "书不存在")
        with database.connection() as conn:
            row = conn.execute("SELECT cover_path, deleted_at FROM books WHERE id = %s", (book_id,)).fetchone()
        # Admins still see covers of unpublished books; readers do not.
        hidden = row is not None and row["deleted_at"] is not None and not role_at_least(principal.role, "viewer")
        if row is None or hidden or not row["cover_path"]:
            raise HTTPException(404, "没有封面")
        path = settings.data_dir / row["cover_path"]
        media = mkbook.COVER_TYPES.get(path.suffix.lower(), "application/octet-stream")
        return FileResponse(path, media_type=media, headers={"Cache-Control": "private, max-age=86400"})

    @app.post(f"{API}/books", status_code=201)
    async def publish(request: Request, file: UploadFile, reason: str | None = None,
                      principal: Principal = Depends(admin)) -> dict:
        return await books.publish(file, principal, request, reason or SCRIPT_REASON)

    @app.delete(f"{API}/books/{{book_id}}")
    def unpublish(book_id: str, request: Request, reason: str | None = None,
                  principal: Principal = Depends(admin)) -> dict:
        row = books.set_deleted(book_id, True, principal, request, reason or SCRIPT_REASON)
        return book_json(row, request)

    @app.get("/", include_in_schema=False)
    def index() -> RedirectResponse:
        return RedirectResponse("/admin/", status_code=302)

    return app


app = create_app() if os.environ.get("DATABASE_URL") else None
