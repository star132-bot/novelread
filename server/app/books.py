"""The global book catalog: publishing, unpublishing and restoring .mkbook packages.

Shared by the public API (/api/v1/books) and the admin API (/api/v1/admin/books); every
change is written to the admin audit log in the same transaction.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
import zipfile
from pathlib import Path

from fastapi import HTTPException, Request, UploadFile

import mkbook

from .accounts import record_audit

API = "/api/v1"


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


def audit_summary(row: dict | None) -> dict | None:
    """What the audit log keeps about a book (no manifest, no file paths)."""
    if row is None:
        return None
    return {
        "revision": row["revision"],
        "title": row["title"],
        "author": row["author"],
        "chapter_count": row["chapter_count"],
        "package_sha256": row["package_sha256"],
        "deleted": row["deleted_at"] is not None,
    }


class BookStore:
    def __init__(self, database, data_dir: Path, max_upload_bytes: int) -> None:
        self.database = database
        self.data_dir = data_dir
        self.books_dir = data_dir / "books"
        self.max_upload_bytes = max_upload_bytes

    def load(self, book_id: str) -> dict:
        if not mkbook.BOOK_ID.match(book_id):
            raise HTTPException(404, "书不存在")
        with self.database.connection() as conn:
            row = conn.execute("SELECT * FROM books WHERE id = %s", (book_id,)).fetchone()
        if row is None or row["deleted_at"] is not None:
            raise HTTPException(404, "书不存在")
        return row

    async def publish(self, file: UploadFile, principal, request: Request, reason: str | None) -> dict:
        fd, temp_name = tempfile.mkstemp(suffix=".mkbook", dir=self.books_dir)
        temp = Path(temp_name)
        try:
            size = 0
            digest = hashlib.sha256()
            with os.fdopen(fd, "wb") as out:
                while chunk := await file.read(1024 * 1024):
                    size += len(chunk)
                    if size > self.max_upload_bytes:
                        raise HTTPException(413, "文件太大")
                    digest.update(chunk)
                    out.write(chunk)
            try:
                manifest = mkbook.validate_package(temp)
            except mkbook.MkBookError as error:
                raise HTTPException(422, f"MKBook 校验失败：{error}") from None
            return self._store(manifest, temp, size, digest.hexdigest(), principal, request, reason)
        finally:
            temp.unlink(missing_ok=True)

    def _store(self, manifest: dict, temp: Path, size: int, sha: str, principal, request: Request,
               reason: str | None) -> dict:
        book_id, revision = manifest["id"], manifest["revision"]
        meta = manifest["metadata"]
        book_dir = self.books_dir / book_id
        book_dir.mkdir(parents=True, exist_ok=True)
        package_rel = f"books/{book_id}/r{revision}.mkbook"
        cover_rel = None
        with self.database.connection() as conn, conn.transaction():
            current = conn.execute("SELECT * FROM books WHERE id = %s FOR UPDATE", (book_id,)).fetchone()
            if current and current["deleted_at"] is None and revision <= current["revision"]:
                raise HTTPException(409, f"云端已有 r{current['revision']}，新版本的 revision 必须更大")
            shutil.copyfile(temp, self.data_dir / package_rel)
            if meta.get("cover"):
                cover_rel = f"books/{book_id}/r{revision}{Path(meta['cover']).suffix}"
                with zipfile.ZipFile(temp) as zf:
                    (self.data_dir / cover_rel).write_bytes(zf.read(meta["cover"]))
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
            record_audit(conn, principal, request, "book.publish", "book", book_id, reason,
                         audit_summary(current), audit_summary(row))
        return book_json(row, request)

    def set_deleted(self, book_id: str, deleted: bool, principal, request: Request, reason: str | None) -> dict:
        """Unpublishes (deleted=True) or restores a book. Files stay on disk either way."""
        with self.database.connection() as conn, conn.transaction():
            current = conn.execute("SELECT * FROM books WHERE id = %s FOR UPDATE", (book_id,)).fetchone()
            if current is None:
                raise HTTPException(404, "书不存在")
            if (current["deleted_at"] is not None) == deleted:
                raise HTTPException(409, "这本书已经下架" if deleted else "这本书没有下架")
            row = conn.execute(
                f"UPDATE books SET deleted_at = {'now()' if deleted else 'NULL'}, seq = nextval('catalog_seq') "
                "WHERE id = %s RETURNING *",
                (book_id,),
            ).fetchone()
            record_audit(conn, principal, request, "book.unpublish" if deleted else "book.restore", "book",
                         book_id, reason, audit_summary(current), audit_summary(row))
        return row
