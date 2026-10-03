"""User accounts, admin roles and the admin audit log.

Every MKauth subject that signs in gets exactly one row in ``users``. Admin access is
role based; a user's effective role is the highest of the role assigned in the admin
console, the role MKauth grants (``roles`` claim) and the ``ADMIN_SUBJECTS`` allow-list.
"""
from __future__ import annotations

import json
from typing import Any, Iterable

ROLES = ("none", "viewer", "operator", "superadmin")
RANK = {role: rank for rank, role in enumerate(ROLES)}
ROLE_NAMES = {"none": "普通用户", "viewer": "只读管理员", "operator": "运营", "superadmin": "超级管理员"}

# MKauth role claim → admin role. "mkread:admin" keeps its original meaning (may publish).
CLAIM_ROLES = {
    "mkread:superadmin": "superadmin",
    "mkread:admin": "superadmin",
    "mkread:operator": "operator",
    "mkread:viewer": "viewer",
}

PERMISSIONS = {
    "viewer": ["read"],
    "operator": ["read", "books.write", "users.write"],
    "superadmin": ["read", "books.write", "users.write", "users.role", "releases.write"],
}

DEFAULT_QUOTA_BYTES = 1024 * 1024 * 1024

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    subject       TEXT PRIMARY KEY,
    display_name  TEXT,
    email         TEXT,
    -- Assigned in the admin console; MKauth may grant a higher role through claim_role.
    role          TEXT NOT NULL DEFAULT 'none' CHECK (role IN ('none', 'viewer', 'operator', 'superadmin')),
    claim_role    TEXT NOT NULL DEFAULT 'none' CHECK (claim_role IN ('none', 'viewer', 'operator', 'superadmin')),
    status        TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    quota_bytes   BIGINT NOT NULL DEFAULT 1073741824 CHECK (quota_bytes >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ
);

-- Accounts that signed in before the users table existed.
INSERT INTO users (subject, display_name, claim_role, created_at, last_seen_at)
SELECT DISTINCT ON (subject) subject, display_name,
       CASE WHEN bool_or(is_admin) OVER (PARTITION BY subject) THEN 'superadmin' ELSE 'none' END,
       min(created_at) OVER (PARTITION BY subject), max(last_used_at) OVER (PARTITION BY subject)
FROM device_sessions
ORDER BY subject, last_used_at DESC
ON CONFLICT (subject) DO NOTHING;

-- Browser sessions of the admin console (cookie; token stored hashed). Sessions opened from a
-- trusted platform (embedded console) carry service, actor_name and role; their subject is
-- "<service>:<actor>" and has no users row.
CREATE TABLE IF NOT EXISTS admin_sessions (
    token_hash    TEXT PRIMARY KEY,
    subject       TEXT NOT NULL,
    ip            TEXT,
    user_agent    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS admin_sessions_subject_idx ON admin_sessions (subject);
ALTER TABLE admin_sessions DROP CONSTRAINT IF EXISTS admin_sessions_subject_fkey;
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS service TEXT;
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS actor_name TEXT;
ALTER TABLE admin_sessions ADD COLUMN IF NOT EXISTS role TEXT;

-- One-time links a trusted platform uses to open the console in an iframe (60 seconds).
CREATE TABLE IF NOT EXISTS admin_embed_tickets (
    ticket_hash   TEXT PRIMARY KEY,
    service       TEXT NOT NULL,
    subject       TEXT NOT NULL,
    actor_name    TEXT,
    role          TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS admin_audit (
    id             BIGSERIAL PRIMARY KEY,
    at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_subject  TEXT NOT NULL,
    actor_name     TEXT,
    actor_role     TEXT NOT NULL,
    action         TEXT NOT NULL,
    resource_type  TEXT NOT NULL,
    resource_id    TEXT NOT NULL,
    reason         TEXT,
    before         JSONB,
    after          JSONB,
    ip             TEXT,
    user_agent     TEXT
);
CREATE INDEX IF NOT EXISTS admin_audit_at_idx ON admin_audit (at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_resource_idx ON admin_audit (resource_type, resource_id);
CREATE INDEX IF NOT EXISTS admin_audit_actor_idx ON admin_audit (actor_subject);

-- The admin console signs in through the same MKauth callback as the app.
ALTER TABLE auth_requests ADD COLUMN IF NOT EXISTS purpose TEXT NOT NULL DEFAULT 'app';
ALTER TABLE auth_requests ADD COLUMN IF NOT EXISTS return_to TEXT;
ALTER TABLE auth_requests ALTER COLUMN app_port DROP NOT NULL;
ALTER TABLE auth_requests ALTER COLUMN app_state DROP NOT NULL;
ALTER TABLE auth_requests ALTER COLUMN app_challenge DROP NOT NULL;
"""


def claim_role(claim_values: Iterable[str]) -> str:
    best = "none"
    for value in claim_values:
        role = CLAIM_ROLES.get(str(value).lower())
        if role and RANK[role] > RANK[best]:
            best = role
    return best


def claim_values(claims: dict) -> list[str]:
    raw = claims.get("roles") or claims.get("role") or claims.get("authorities") or []
    if isinstance(raw, str):
        raw = raw.replace(",", " ").split()
    return [str(item) for item in raw] if isinstance(raw, list) else []


def effective_role(user: dict, admin_subjects: Iterable[str]) -> str:
    if user["subject"] in admin_subjects:
        return "superadmin"
    return max(user.get("role") or "none", user.get("claim_role") or "none", key=RANK.__getitem__)


def role_at_least(role: str, required: str) -> bool:
    return RANK.get(role, 0) >= RANK[required]


def effective_rank_sql(alias: str = "u") -> str:
    """SQL for a user's effective role rank; takes the ADMIN_SUBJECTS array as one parameter."""
    def rank(column: str) -> str:
        return (f"(CASE {alias}.{column} WHEN 'superadmin' THEN 3 WHEN 'operator' THEN 2 "
                f"WHEN 'viewer' THEN 1 ELSE 0 END)")
    return f"(CASE WHEN {alias}.subject = ANY(%s) THEN 3 ELSE GREATEST({rank('role')}, {rank('claim_role')}) END)"


def upsert_user(conn, subject: str, display_name: str | None, email: str | None, role_from_claims: str) -> dict:
    return conn.execute(
        """
        INSERT INTO users (subject, display_name, email, claim_role, last_seen_at)
        VALUES (%s, %s, %s, %s, now())
        ON CONFLICT (subject) DO UPDATE SET
            display_name = COALESCE(EXCLUDED.display_name, users.display_name),
            email = COALESCE(EXCLUDED.email, users.email),
            claim_role = EXCLUDED.claim_role,
            last_seen_at = now()
        RETURNING *
        """,
        (subject, display_name, email, role_from_claims),
    ).fetchone()


def _jsonable(value: Any) -> Any:
    if value is None:
        return None
    return json.dumps(value, ensure_ascii=False, default=str)


class _CliPrincipal:
    subject = "server-cli"
    name = "服务器命令行"
    role = "superadmin"


def main(argv: list[str]) -> int:
    """Server-side role management, e.g. to appoint the first superadmin:

        docker compose exec api python -m app.accounts grant <subject-or-email> superadmin --reason "首位管理员"
        docker compose exec api python -m app.accounts admins
    """
    import argparse
    import os
    import sys

    from .db import Database

    parser = argparse.ArgumentParser(prog="python -m app.accounts")
    sub = parser.add_subparsers(dest="command", required=True)
    grant = sub.add_parser("grant", help="assign a console role (the user must have signed in once)")
    grant.add_argument("who", help="MKauth subject or email")
    grant.add_argument("role", choices=ROLES)
    grant.add_argument("--reason", default="服务器命令行授权")
    sub.add_parser("admins", help="list users with an assigned or MKauth-granted admin role")
    args = parser.parse_args(argv)

    database = Database(os.environ["DATABASE_URL"])
    database.open()
    try:
        with database.connection() as conn, conn.transaction():
            if args.command == "admins":
                for row in conn.execute(
                    "SELECT subject, display_name, email, role, claim_role, status FROM users "
                    "WHERE role <> 'none' OR claim_role <> 'none' ORDER BY display_name"
                ).fetchall():
                    print(f"{row['subject']}\t{row['display_name'] or ''}\t{row['email'] or ''}\t"
                          f"assigned={row['role']}\tmkauth={row['claim_role']}\t{row['status']}")
                return 0
            matches = conn.execute("SELECT * FROM users WHERE subject = %s OR lower(email) = lower(%s) FOR UPDATE",
                                   (args.who, args.who)).fetchall()
            if len(matches) != 1:
                print("找不到唯一匹配的用户（需要先用 MKauth 登录一次 App 或管理平台）" if not matches
                      else "匹配到多个用户，请改用 subject", file=sys.stderr)
                return 1
            user = matches[0]
            conn.execute("UPDATE users SET role = %s WHERE subject = %s", (args.role, user["subject"]))
            record_audit(conn, _CliPrincipal(), None, "user.role", "user", user["subject"], args.reason,
                         {"role": user["role"]}, {"role": args.role})
            print(f"{user['display_name'] or user['subject']}: {user['role']} -> {args.role}")
    finally:
        database.close()
    return 0


def record_audit(conn, principal, request, action: str, resource_type: str, resource_id: str,
                 reason: str | None, before: Any = None, after: Any = None) -> None:
    """Writes one audit entry; call it inside the transaction that made the change."""
    ip = None
    user_agent = None
    if request is not None:
        ip = request.headers.get("cf-connecting-ip") or (request.client.host if request.client else None)
        user_agent = (request.headers.get("user-agent") or "")[:200]
    conn.execute(
        """
        INSERT INTO admin_audit (actor_subject, actor_name, actor_role, action, resource_type, resource_id,
                                 reason, before, after, ip, user_agent)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        (principal.subject, principal.name, principal.role, action, resource_type, str(resource_id),
         reason, _jsonable(before), _jsonable(after), ip, user_agent),
    )


if __name__ == "__main__":
    import sys

    sys.exit(main(sys.argv[1:]))
