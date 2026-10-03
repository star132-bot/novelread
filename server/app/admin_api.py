"""Admin API (/api/v1/admin) and the admin console (/admin).

Conventions shared by every resource (see docs/cloud-library.md, 管理平台):

* Lists take ``page`` (from 1), ``page_size`` (≤ 200), ``sort`` (a field, ``-field`` for
  descending) and resource filters, and return ``{items, page, page_size, total}``;
  ``format=csv`` exports up to 10 000 rows instead.
* Errors are ``{code, message, details}``.
* Every write carries a ``reason`` and is audited in the same transaction.
* Roles: viewer reads, operator manages books and users, superadmin also assigns roles and
  manages app releases.
"""
from __future__ import annotations

import csv
import io
import shutil
import time
from datetime import datetime
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, FastAPI, Form, HTTPException, Query, Request, UploadFile
from fastapi.exception_handlers import http_exception_handler, request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.openapi.docs import get_swagger_ui_html
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from .accounts import (
    PERMISSIONS,
    RANK,
    ROLE_NAMES,
    effective_rank_sql,
    effective_role,
    record_audit,
    role_at_least,
)
from .auth import Authenticator, Principal
from .books import BookStore

PREFIX = "/api/v1/admin"
UI_DIR = Path(__file__).parent / "admin_ui"
CSV_LIMIT = 10_000
MAX_QUOTA_BYTES = 1024 ** 4

ERROR_CODES = {
    400: "bad_request", 401: "unauthorized", 403: "forbidden", 404: "not_found", 405: "method_not_allowed",
    409: "conflict", 413: "too_large", 422: "invalid_request", 502: "upstream_error", 503: "unavailable",
}

UI_HEADERS = {
    "Cache-Control": "no-cache",
    "Content-Security-Policy": (
        "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; "
        "frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    ),
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "same-origin",
}


class ApiError(HTTPException):
    def __init__(self, status: int, code: str, message: str, details: object = None) -> None:
        super().__init__(status, message)
        self.code = code
        self.details = details


# ------------------------------------------------------------------ request models


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class Reason(Strict):
    reason: str = Field(min_length=2, max_length=200, description="操作原因，写入审计日志")


class UserPatch(Reason):
    status: Literal["active", "disabled"] | None = None
    role: Literal["none", "viewer", "operator", "superadmin"] | None = Field(
        None, description="在管理平台分配的角色（MKauth 授予的角色另计，取较高者）")
    quota_bytes: int | None = Field(None, ge=0, le=MAX_QUOTA_BYTES)


class BookBatch(Reason):
    action: Literal["unpublish", "restore"]
    ids: list[str] = Field(min_length=1, max_length=100)


class ReleasePatch(Reason):
    min_supported: int = Field(ge=0, description="低于此 versionCode 的安装强制更新")


# ------------------------------------------------------------------ helpers


def order_by(sort: str | None, allowed: dict[str, str], default: str, tiebreak: str) -> str:
    key = sort or default
    field = key.lstrip("-")
    if field not in allowed:
        raise ApiError(422, "invalid_sort", f"不支持按 {field} 排序", {"allowed": sorted(allowed)})
    direction = "DESC" if key.startswith("-") else "ASC"
    return f"{allowed[field]} {direction} NULLS LAST, {tiebreak}"


def iso(value):
    return value.isoformat() if isinstance(value, datetime) else value


def listing(conn, select: str, frm: str, where: list[str], params: list, order: str,
            page: int, page_size: int, fmt: str) -> tuple[list[dict], int]:
    clause = f" WHERE {' AND '.join(where)}" if where else ""
    total = conn.execute(f"SELECT count(*) AS n {frm}{clause}", params).fetchone()["n"]
    if fmt == "csv":
        limit, offset = CSV_LIMIT, 0
    else:
        limit, offset = page_size, (page - 1) * page_size
    rows = conn.execute(f"SELECT {select} {frm}{clause} ORDER BY {order} LIMIT %s OFFSET %s",
                        [*params, limit, offset]).fetchall()
    return rows, total


def respond(items: list[dict], total: int, page: int, page_size: int, fmt: str, name: str,
            columns: list[tuple[str, str]]):
    if fmt == "csv":
        out = io.StringIO()
        out.write("﻿")
        writer = csv.writer(out)
        writer.writerow([label for _key, label in columns])
        for item in items:
            row = []
            for key, _label in columns:
                value = item.get(key)
                if isinstance(value, list):
                    value = " ".join(map(str, value))
                row.append("" if value is None else iso(value))
            writer.writerow(row)
        stamp = datetime.now().strftime("%Y%m%d-%H%M")
        return Response(out.getvalue(), media_type="text/csv; charset=utf-8",
                        headers={"Content-Disposition": f'attachment; filename="mkread-{name}-{stamp}.csv"'})
    return {"items": items, "page": page, "page_size": page_size, "total": total}


def like(q: str) -> str:
    return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"


# ------------------------------------------------------------------ install


def install(app: FastAPI, settings, database, auth: Authenticator, books: BookStore) -> None:
    admin_subjects = settings.admin_subjects

    async def admin_http_error(request: Request, exc: StarletteHTTPException):
        if not request.url.path.startswith(PREFIX):
            return await http_exception_handler(request, exc)
        body = {
            "code": getattr(exc, "code", None) or ERROR_CODES.get(exc.status_code, "error"),
            "message": exc.detail if isinstance(exc.detail, str) else "请求失败",
            "details": getattr(exc, "details", None),
        }
        return JSONResponse(body, status_code=exc.status_code, headers=getattr(exc, "headers", None))

    async def admin_validation_error(request: Request, exc: RequestValidationError):
        if not request.url.path.startswith(PREFIX):
            return await request_validation_exception_handler(request, exc)
        details = [{"field": ".".join(str(p) for p in error["loc"][1:]), "message": error["msg"]}
                   for error in exc.errors()]
        return JSONResponse({"code": "invalid_request", "message": "参数不合法", "details": details},
                            status_code=422)

    app.add_exception_handler(StarletteHTTPException, admin_http_error)
    app.add_exception_handler(RequestValidationError, admin_validation_error)

    def requires(required: str):
        def dependency(request: Request) -> Principal:
            return auth.require_role(request, required)
        return dependency

    viewer, operator, superadmin = requires("viewer"), requires("operator"), requires("superadmin")
    api = APIRouter(prefix=PREFIX, tags=["admin"])

    # -------------------------------------------------------------- session and search

    @api.get("/session", summary="当前登录的管理员")
    def session(principal: Principal = Depends(viewer)) -> dict:
        return {
            "subject": principal.subject,
            "name": principal.name,
            "role": principal.role,
            "role_name": ROLE_NAMES[principal.role],
            "permissions": PERMISSIONS[principal.role],
            "via": principal.via,
            "login_enabled": settings.login_enabled,
        }

    @api.get("/overview", summary="概览：用户、书籍、存储、版本、健康状态")
    def overview(_principal: Principal = Depends(viewer)) -> dict:
        started = time.perf_counter()
        with database.connection() as conn:
            conn.execute("SELECT 1")
            db_ms = round((time.perf_counter() - started) * 1000, 1)
            users = conn.execute(
                f"""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE last_seen_at > now() - interval '7 days') AS active_7d,
                       count(*) FILTER (WHERE created_at > now() - interval '7 days') AS new_7d,
                       count(*) FILTER (WHERE status = 'disabled') AS disabled,
                       count(*) FILTER (WHERE {effective_rank_sql()} > 0) AS admins
                FROM users u
                """,
                (admin_subjects,),
            ).fetchone()
            book_stats = conn.execute(
                """
                SELECT count(*) FILTER (WHERE deleted_at IS NULL) AS active,
                       count(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted,
                       COALESCE(sum(char_count) FILTER (WHERE deleted_at IS NULL), 0)::bigint AS chars
                FROM books
                """
            ).fetchone()
            storage = conn.execute(
                """
                SELECT (SELECT COALESCE(sum(package_size), 0)::bigint FROM book_revisions) AS books,
                       (SELECT count(*) FROM book_revisions) AS book_revisions,
                       (SELECT COALESCE(sum(package_size), 0)::bigint FROM voice_packs) AS voices,
                       (SELECT COALESCE(sum(apk_size), 0)::bigint FROM app_releases) AS releases
                """
            ).fetchone()
            latest = conn.execute(
                "SELECT version_code, version_name, published_at, min_supported FROM app_releases "
                "WHERE withdrawn_at IS NULL ORDER BY version_code DESC LIMIT 1"
            ).fetchone()
            signups = conn.execute(
                """
                SELECT d::date AS day,
                       (SELECT count(*) FROM users WHERE created_at::date = d::date) AS signups,
                       (SELECT count(*) FROM users WHERE last_seen_at::date = d::date) AS active
                FROM generate_series(current_date - 13, current_date, interval '1 day') AS d
                ORDER BY d
                """
            ).fetchall()
            recent = conn.execute(
                "SELECT id, at, actor_name, actor_subject, action, resource_type, resource_id, reason "
                "FROM admin_audit ORDER BY at DESC LIMIT 8"
            ).fetchall()
        disk = shutil.disk_usage(settings.data_dir)
        return {
            "users": users,
            "books": book_stats,
            "storage": {**storage, "disk_total": disk.total, "disk_free": disk.free},
            "latest_release": latest,
            "daily": [{**row, "day": row["day"].isoformat()} for row in signups],
            "recent_audit": recent,
            "health": {
                "database": "ok",
                "database_ms": db_ms,
                "login": "ok" if settings.login_enabled else "not_configured",
                "read_access": settings.read_access,
            },
        }

    @api.get("/search", summary="全局搜索用户和书籍")
    def search(q: str = Query(..., min_length=1, max_length=100), _principal: Principal = Depends(viewer)) -> dict:
        pattern = like(q)
        with database.connection() as conn:
            users = conn.execute(
                "SELECT subject, display_name, email, status FROM users "
                "WHERE subject = %s OR display_name ILIKE %s OR email ILIKE %s "
                "ORDER BY last_seen_at DESC NULLS LAST LIMIT 8",
                (q, pattern, pattern),
            ).fetchall()
            found = conn.execute(
                "SELECT id, title, author, deleted_at IS NOT NULL AS deleted FROM books "
                "WHERE id ILIKE %s OR title ILIKE %s OR author ILIKE %s ORDER BY published_at DESC LIMIT 8",
                (pattern, pattern, pattern),
            ).fetchall()
        return {"users": users, "books": found}

    # -------------------------------------------------------------- users

    user_select = """
        u.*, COALESCE(s.active_sessions, 0) AS active_sessions, s.last_used_at,
        (SELECT count(*) FROM books b WHERE b.published_by = u.subject) AS published_books
    """
    user_from = """
        FROM users u LEFT JOIN (
            SELECT subject,
                   count(*) FILTER (WHERE revoked_at IS NULL AND expires_at > now()) AS active_sessions,
                   max(last_used_at) AS last_used_at
            FROM device_sessions GROUP BY subject
        ) s ON s.subject = u.subject
    """
    user_sorts = {
        "created_at": "u.created_at", "last_seen_at": "u.last_seen_at", "display_name": "u.display_name",
        "active_sessions": "COALESCE(s.active_sessions, 0)", "quota_bytes": "u.quota_bytes",
    }
    user_columns = [
        ("subject", "Subject"), ("display_name", "名称"), ("email", "邮箱"), ("role", "角色"),
        ("status", "状态"), ("active_sessions", "有效设备"), ("quota_bytes", "配额（字节）"),
        ("created_at", "注册时间"), ("last_seen_at", "最近活跃"),
    ]

    def user_json(row: dict) -> dict:
        role_value = effective_role(row, admin_subjects)
        if row["subject"] in admin_subjects:
            source = "allowlist"
        elif row["claim_role"] != "none" and RANK[row["claim_role"]] >= RANK[row["role"]]:
            source = "mkauth"
        elif row["role"] != "none":
            source = "console"
        else:
            source = None
        return {
            "subject": row["subject"],
            "display_name": row["display_name"],
            "email": row["email"],
            "status": row["status"],
            "role": role_value,
            "assigned_role": row["role"],
            "claim_role": row["claim_role"],
            "role_source": source,
            "quota_bytes": row["quota_bytes"],
            "storage_used_bytes": 0,
            "created_at": row["created_at"],
            "last_seen_at": row["last_seen_at"],
            "active_sessions": row.get("active_sessions", 0),
            "last_used_at": row.get("last_used_at"),
            "published_books": row.get("published_books", 0),
        }

    def load_user(conn, subject: str, lock: bool = False) -> dict:
        row = conn.execute(
            f"SELECT {user_select} {user_from} WHERE u.subject = %s" if not lock
            else "SELECT * FROM users WHERE subject = %s FOR UPDATE",
            (subject,),
        ).fetchone()
        if row is None:
            raise ApiError(404, "user_not_found", "用户不存在")
        return row

    def guard_target(principal: Principal, target: dict, what: str) -> None:
        if target["subject"] == principal.subject:
            raise ApiError(409, "self_change", f"不能{what}自己的账号")
        if RANK[effective_role(target, admin_subjects)] >= RANK[principal.role] and principal.role != "superadmin":
            raise ApiError(403, "forbidden", f"不能{what}与自己同级或更高级别的管理员")

    @api.get("/users", summary="用户列表")
    def list_users(
        q: str | None = Query(None, max_length=100, description="名称、邮箱或 subject"),
        status: Literal["active", "disabled"] | None = None,
        role: Literal["none", "viewer", "operator", "superadmin", "admin"] | None = Query(
            None, description="按有效角色筛选；admin 表示任意管理角色"),
        page: int = Query(1, ge=1),
        page_size: int = Query(20, ge=1, le=200),
        sort: str | None = None,
        format: Literal["json", "csv"] = "json",
        _principal: Principal = Depends(viewer),
    ):
        where, params = [], []
        if q:
            where.append("(u.subject = %s OR u.display_name ILIKE %s OR u.email ILIKE %s)")
            params += [q, like(q), like(q)]
        if status:
            where.append("u.status = %s")
            params.append(status)
        if role == "admin":
            where.append(f"{effective_rank_sql()} > 0")
            params.append(admin_subjects)
        elif role:
            where.append(f"{effective_rank_sql()} = %s")
            params += [admin_subjects, RANK[role]]
        with database.connection() as conn:
            rows, total = listing(conn, user_select, user_from, where, params,
                                  order_by(sort, user_sorts, "-last_seen_at", "u.subject"),
                                  page, page_size, format)
        return respond([user_json(r) for r in rows], total, page, page_size, format, "users", user_columns)

    @api.get("/users/{subject}", summary="用户详情：设备会话、发布的书、相关审计")
    def get_user(subject: str, _principal: Principal = Depends(viewer)) -> dict:
        with database.connection() as conn:
            row = load_user(conn, subject)
            sessions = conn.execute(
                """
                SELECT left(token_hash, 12) AS id, user_agent, created_at, last_used_at, expires_at, revoked_at,
                       CASE WHEN revoked_at IS NOT NULL THEN 'revoked'
                            WHEN expires_at <= now() THEN 'expired' ELSE 'active' END AS state
                FROM device_sessions WHERE subject = %s ORDER BY last_used_at DESC LIMIT 50
                """,
                (subject,),
            ).fetchall()
            console = conn.execute(
                "SELECT count(*) AS n, max(last_used_at) AS last_used_at FROM admin_sessions "
                "WHERE subject = %s AND expires_at > now()",
                (subject,),
            ).fetchone()
            audit = conn.execute(
                "SELECT id, at, actor_name, actor_subject, action, reason, before, after FROM admin_audit "
                "WHERE resource_type = 'user' AND resource_id = %s ORDER BY at DESC LIMIT 20",
                (subject,),
            ).fetchall()
        return {**user_json(row), "sessions": sessions, "console_sessions": console, "audit": audit}

    @api.patch("/users/{subject}", summary="停用 / 启用用户、调整配额、分配角色")
    def patch_user(subject: str, body: UserPatch, request: Request,
                   principal: Principal = Depends(operator)) -> dict:
        if body.status is None and body.role is None and body.quota_bytes is None:
            raise ApiError(422, "nothing_to_change", "没有要修改的字段")
        if body.role is not None and not role_at_least(principal.role, "superadmin"):
            raise ApiError(403, "forbidden", "需要超级管理员权限才能分配角色")
        with database.connection() as conn, conn.transaction():
            target = load_user(conn, subject, lock=True)
            if body.status is not None or body.role is not None:
                guard_target(principal, target, "修改")
            changes = []
            if body.status is not None and body.status != target["status"]:
                changes.append(("status", "user.enable" if body.status == "active" else "user.disable"))
            if body.role is not None and body.role != target["role"]:
                changes.append(("role", "user.role"))
            if body.quota_bytes is not None and body.quota_bytes != target["quota_bytes"]:
                changes.append(("quota_bytes", "user.quota"))
            if not changes:
                raise ApiError(409, "no_change", "数据没有变化")
            values = body.model_dump()
            for field, action in changes:
                conn.execute(f"UPDATE users SET {field} = %s WHERE subject = %s", (values[field], subject))
                record_audit(conn, principal, request, action, "user", subject, body.reason,
                             {field: target[field]}, {field: values[field]})
            if body.status == "disabled":
                conn.execute("DELETE FROM admin_sessions WHERE subject = %s", (subject,))
            row = load_user(conn, subject)
        return user_json(row)

    @api.post("/users/{subject}/revoke-sessions", summary="吊销该用户的全部设备会话")
    def revoke_sessions(subject: str, body: Reason, request: Request,
                        principal: Principal = Depends(operator)) -> dict:
        with database.connection() as conn, conn.transaction():
            target = load_user(conn, subject, lock=True)
            if target["subject"] != principal.subject:
                guard_target(principal, target, "吊销")
            revoked = conn.execute(
                "UPDATE device_sessions SET revoked_at = now() "
                "WHERE subject = %s AND revoked_at IS NULL AND expires_at > now()",
                (subject,),
            ).rowcount
            console = conn.execute("DELETE FROM admin_sessions WHERE subject = %s", (subject,)).rowcount
            record_audit(conn, principal, request, "user.revoke_sessions", "user", subject, body.reason,
                         {"active_sessions": revoked, "console_sessions": console},
                         {"active_sessions": 0, "console_sessions": 0})
        return {"revoked": revoked, "console_sessions": console}

    # -------------------------------------------------------------- books

    book_select = """
        b.id, b.title, b.author, b.revision, b.status, b.tags, b.chapter_count, b.char_count, b.package_size,
        b.cover_path IS NOT NULL AS has_cover, b.published_at, b.published_by, b.deleted_at, b.seq,
        (SELECT count(*) FROM book_revisions r WHERE r.book_id = b.id) AS revisions,
        (SELECT display_name FROM users u WHERE u.subject = b.published_by) AS published_by_name
    """
    book_sorts = {
        "published_at": "b.published_at", "title": "b.title", "author": "b.author", "revision": "b.revision",
        "char_count": "b.char_count", "chapter_count": "b.chapter_count", "package_size": "b.package_size",
    }
    book_columns = [
        ("id", "ID"), ("title", "书名"), ("author", "作者"), ("revision", "版本"), ("state", "状态"),
        ("chapter_count", "章节数"), ("char_count", "字数"), ("package_size", "包大小（字节）"),
        ("tags", "标签"), ("published_at", "发布时间"), ("published_by", "发布人"),
    ]

    def book_row(row: dict) -> dict:
        return {**row, "state": "deleted" if row["deleted_at"] is not None else "active"}

    @api.get("/books", summary="全局书库列表")
    def list_books(
        q: str | None = Query(None, max_length=100, description="书名、作者或 ID"),
        state: Literal["active", "deleted"] | None = None,
        tag: str | None = Query(None, max_length=50),
        page: int = Query(1, ge=1),
        page_size: int = Query(20, ge=1, le=200),
        sort: str | None = None,
        format: Literal["json", "csv"] = "json",
        _principal: Principal = Depends(viewer),
    ):
        where, params = [], []
        if q:
            where.append("(b.id ILIKE %s OR b.title ILIKE %s OR b.author ILIKE %s)")
            params += [like(q)] * 3
        if state == "active":
            where.append("b.deleted_at IS NULL")
        elif state == "deleted":
            where.append("b.deleted_at IS NOT NULL")
        if tag:
            where.append("%s = ANY(b.tags)")
            params.append(tag)
        with database.connection() as conn:
            rows, total = listing(conn, book_select, "FROM books b", where, params,
                                  order_by(sort, book_sorts, "-published_at", "b.id"), page, page_size, format)
        return respond([book_row(r) for r in rows], total, page, page_size, format, "books", book_columns)

    @api.get("/books/{book_id}", summary="书籍详情：清单摘要、版本历史、审计")
    def get_book(book_id: str, _principal: Principal = Depends(viewer)) -> dict:
        with database.connection() as conn:
            row = conn.execute(f"SELECT {book_select}, b.description, b.manifest, b.package_sha256 "
                               "FROM books b WHERE b.id = %s", (book_id,)).fetchone()
            if row is None:
                raise ApiError(404, "book_not_found", "书不存在")
            revisions = conn.execute(
                "SELECT r.revision, r.package_size, r.package_sha256, r.published_at, r.published_by, "
                "u.display_name AS published_by_name FROM book_revisions r "
                "LEFT JOIN users u ON u.subject = r.published_by "
                "WHERE r.book_id = %s ORDER BY r.revision DESC",
                (book_id,),
            ).fetchall()
            audit = conn.execute(
                "SELECT id, at, actor_name, actor_subject, action, reason FROM admin_audit "
                "WHERE resource_type = 'book' AND resource_id = %s ORDER BY at DESC LIMIT 20",
                (book_id,),
            ).fetchall()
        manifest = row.pop("manifest") or {}
        chapters = manifest.get("chapters") or []
        volumes: list[dict] = []
        for chapter in chapters:
            name = chapter.get("volume") or ""
            if not volumes or volumes[-1]["name"] != name:
                volumes.append({"name": name, "chapters": 0, "chars": 0})
            volumes[-1]["chapters"] += 1
            volumes[-1]["chars"] += chapter.get("chars", 0)
        return {
            **book_row(row),
            "language": (manifest.get("metadata") or {}).get("language"),
            "volumes": volumes,
            "first_chapters": [{"id": c.get("id"), "title": c.get("title"), "chars": c.get("chars")}
                               for c in chapters[:10]],
            "revision_history": revisions,
            "audit": audit,
        }

    @api.post("/books", status_code=201, summary="上传 .mkbook（新书或新版本）")
    async def upload_book(request: Request, file: UploadFile,
                          reason: str = Form(..., min_length=2, max_length=200),
                          principal: Principal = Depends(operator)) -> dict:
        return await books.publish(file, principal, request, reason.strip())

    def change_book(book_id: str, deleted: bool, principal: Principal, request: Request, reason: str) -> dict:
        row = books.set_deleted(book_id, deleted, principal, request, reason)
        return {"id": row["id"], "state": "deleted" if row["deleted_at"] else "active", "seq": row["seq"]}

    @api.post("/books/{book_id}/unpublish", summary="下架（已下载的设备保留，不再更新）")
    def unpublish(book_id: str, body: Reason, request: Request, principal: Principal = Depends(operator)) -> dict:
        return change_book(book_id, True, principal, request, body.reason)

    @api.post("/books/{book_id}/restore", summary="恢复上架")
    def restore(book_id: str, body: Reason, request: Request, principal: Principal = Depends(operator)) -> dict:
        return change_book(book_id, False, principal, request, body.reason)

    @api.post("/books/batch", summary="批量下架 / 恢复")
    def batch(body: BookBatch, request: Request, principal: Principal = Depends(operator)) -> dict:
        results = []
        for book_id in dict.fromkeys(body.ids):
            try:
                results.append({"id": book_id, "ok": True,
                                **change_book(book_id, body.action == "unpublish", principal, request, body.reason)})
            except HTTPException as error:
                results.append({"id": book_id, "ok": False, "message": error.detail})
        return {"results": results, "succeeded": sum(r["ok"] for r in results)}

    # -------------------------------------------------------------- voice packs and releases

    @api.get("/voice-packs", summary="音色包")
    def voice_packs(_principal: Principal = Depends(viewer)) -> dict:
        with database.connection() as conn:
            rows = conn.execute(
                "SELECT id, revision, package_size, package_sha256, file_count, unpacked_size, published_at "
                "FROM voice_packs ORDER BY id"
            ).fetchall()
        return {"items": rows, "page": 1, "page_size": len(rows), "total": len(rows)}

    release_columns = [
        ("version_code", "versionCode"), ("version_name", "版本"), ("state", "状态"), ("min_supported", "最低支持"),
        ("apk_size", "大小（字节）"), ("apk_sha256", "SHA-256"), ("published_at", "发布时间"), ("notes", "说明"),
    ]

    def release_rows(conn) -> list[dict]:
        rows = conn.execute("SELECT * FROM app_releases ORDER BY version_code DESC").fetchall()
        latest = next((r["version_code"] for r in rows if r["withdrawn_at"] is None), None)
        return [{**{k: v for k, v in r.items() if k != "apk_path"},
                 "state": "withdrawn" if r["withdrawn_at"] else "active",
                 "is_latest": r["version_code"] == latest} for r in rows]

    @api.get("/releases", summary="App 版本")
    def list_releases(format: Literal["json", "csv"] = "json", _principal: Principal = Depends(viewer)):
        with database.connection() as conn:
            rows = release_rows(conn)
        return respond(rows, len(rows), 1, len(rows), format, "releases", release_columns)

    def change_release(version_code: int, principal: Principal, request: Request, reason: str,
                       action: str, sql: str, params: tuple, check) -> dict:
        with database.connection() as conn, conn.transaction():
            current = conn.execute("SELECT * FROM app_releases WHERE version_code = %s FOR UPDATE",
                                   (version_code,)).fetchone()
            if current is None:
                raise ApiError(404, "release_not_found", "版本不存在")
            check(current)
            row = conn.execute(sql, (*params, version_code)).fetchone()
            keep = ("version_name", "withdrawn_at", "min_supported")
            record_audit(conn, principal, request, action, "release", str(version_code), reason,
                         {k: current[k] for k in keep}, {k: row[k] for k in keep})
            return next(r for r in release_rows(conn) if r["version_code"] == version_code)

    @api.post("/releases/{version_code}/withdraw", summary="撤回版本（客户端不再收到这个更新）")
    def withdraw(version_code: int, body: Reason, request: Request,
                 principal: Principal = Depends(superadmin)) -> dict:
        def check(row):
            if row["withdrawn_at"] is not None:
                raise ApiError(409, "already_withdrawn", "这个版本已经撤回")
        return change_release(version_code, principal, request, body.reason, "release.withdraw",
                              "UPDATE app_releases SET withdrawn_at = now() WHERE version_code = %s RETURNING *",
                              (), check)

    @api.post("/releases/{version_code}/restore", summary="恢复已撤回的版本")
    def restore_release(version_code: int, body: Reason, request: Request,
                        principal: Principal = Depends(superadmin)) -> dict:
        def check(row):
            if row["withdrawn_at"] is None:
                raise ApiError(409, "not_withdrawn", "这个版本没有撤回")
        return change_release(version_code, principal, request, body.reason, "release.restore",
                              "UPDATE app_releases SET withdrawn_at = NULL WHERE version_code = %s RETURNING *",
                              (), check)

    @api.patch("/releases/{version_code}", summary="设置最低支持版本（强制更新）")
    def patch_release(version_code: int, body: ReleasePatch, request: Request,
                      principal: Principal = Depends(superadmin)) -> dict:
        def check(row):
            if body.min_supported > version_code:
                raise ApiError(422, "invalid_min_supported", "最低支持版本不能高于这个版本本身")
            if body.min_supported == row["min_supported"]:
                raise ApiError(409, "no_change", "数据没有变化")
        return change_release(version_code, principal, request, body.reason, "release.min_supported",
                              "UPDATE app_releases SET min_supported = %s WHERE version_code = %s RETURNING *",
                              (body.min_supported,), check)

    # -------------------------------------------------------------- audit

    audit_sorts = {"at": "a.at", "action": "a.action", "actor_name": "a.actor_name"}
    audit_columns = [
        ("id", "ID"), ("at", "时间"), ("actor_name", "操作人"), ("actor_subject", "操作人 Subject"),
        ("actor_role", "角色"), ("action", "操作"), ("resource_type", "对象类型"), ("resource_id", "对象"),
        ("reason", "原因"), ("ip", "IP"),
    ]

    @api.get("/audit", summary="审计日志")
    def list_audit(
        actor: str | None = Query(None, max_length=100, description="操作人 subject 或名称"),
        action: str | None = Query(None, max_length=50, description="操作前缀，如 user. 或 book.unpublish"),
        resource_type: Literal["user", "book", "release"] | None = None,
        resource_id: str | None = Query(None, max_length=200),
        since: datetime | None = None,
        until: datetime | None = None,
        page: int = Query(1, ge=1),
        page_size: int = Query(20, ge=1, le=200),
        sort: str | None = None,
        format: Literal["json", "csv"] = "json",
        _principal: Principal = Depends(viewer),
    ):
        where, params = [], []
        if actor:
            where.append("(a.actor_subject = %s OR a.actor_name ILIKE %s)")
            params += [actor, like(actor)]
        if action:
            where.append("a.action LIKE %s")
            params.append(action.replace("%", "") + "%")
        if resource_type:
            where.append("a.resource_type = %s")
            params.append(resource_type)
        if resource_id:
            where.append("a.resource_id = %s")
            params.append(resource_id)
        if since:
            where.append("a.at >= %s")
            params.append(since)
        if until:
            where.append("a.at < %s")
            params.append(until)
        with database.connection() as conn:
            rows, total = listing(conn, "a.*", "FROM admin_audit a", where, params,
                                  order_by(sort, audit_sorts, "-at", "a.id DESC"), page, page_size, format)
        return respond(rows, total, page, page_size, format, "audit", audit_columns)

    # -------------------------------------------------------------- API docs (admins only)

    @api.get("/openapi.json", include_in_schema=False)
    def openapi(_principal: Principal = Depends(viewer)) -> dict:
        return app.openapi()

    app.include_router(api)

    # -------------------------------------------------------------- console UI

    @app.get("/admin", include_in_schema=False)
    def console_redirect() -> RedirectResponse:
        return RedirectResponse("/admin/", status_code=308)

    @app.get("/admin/", include_in_schema=False)
    def console() -> FileResponse:
        return FileResponse(UI_DIR / "index.html", media_type="text/html; charset=utf-8", headers=UI_HEADERS)

    @app.get("/admin/api-docs", include_in_schema=False)
    def api_docs(request: Request):
        principal = auth.principal(request)
        if principal is None or not role_at_least(principal.role, "viewer"):
            return RedirectResponse("/admin/", status_code=302)
        return get_swagger_ui_html(openapi_url=f"{PREFIX}/openapi.json", title="MKread 管理接口")

    app.mount("/admin/assets", StaticFiles(directory=UI_DIR), name="admin-assets")

    # Kept for older bookmarks of the publishing page.
    @app.get("/admin.html", include_in_schema=False)
    def legacy_admin() -> RedirectResponse:
        return RedirectResponse("/admin/", status_code=301)
