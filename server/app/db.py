"""PostgreSQL access. Schema is created idempotently at startup."""
from __future__ import annotations

from contextlib import contextmanager

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .voices import SCHEMA as VOICE_SCHEMA

SCHEMA = """
CREATE SEQUENCE IF NOT EXISTS catalog_seq;

CREATE TABLE IF NOT EXISTS books (
    id              TEXT PRIMARY KEY,
    revision        INTEGER NOT NULL,
    title           TEXT NOT NULL,
    author          TEXT,
    status          TEXT,
    tags            TEXT[] NOT NULL DEFAULT '{}',
    description     TEXT,
    chapter_count   INTEGER NOT NULL,
    char_count      BIGINT NOT NULL,
    cover_path      TEXT,
    manifest        JSONB NOT NULL,
    package_path    TEXT NOT NULL,
    package_sha256  TEXT NOT NULL,
    package_size    BIGINT NOT NULL,
    published_by    TEXT NOT NULL,
    published_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    -- Monotonic change cursor: clients ask for everything with seq > last seen.
    seq             BIGINT NOT NULL DEFAULT nextval('catalog_seq')
);
CREATE INDEX IF NOT EXISTS books_seq_idx ON books (seq);

CREATE TABLE IF NOT EXISTS book_revisions (
    book_id         TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    revision        INTEGER NOT NULL,
    package_path    TEXT NOT NULL,
    package_sha256  TEXT NOT NULL,
    package_size    BIGINT NOT NULL,
    published_by    TEXT NOT NULL,
    published_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (book_id, revision)
);

-- Sign-in in flight: browser is at MKauth.
CREATE TABLE IF NOT EXISTS auth_requests (
    state           TEXT PRIMARY KEY,
    server_verifier TEXT NOT NULL,
    app_port        INTEGER NOT NULL,
    app_state       TEXT NOT NULL,
    app_challenge   TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One-time codes handed to the app's loopback listener (stored hashed).
CREATE TABLE IF NOT EXISTS auth_codes (
    code_hash       TEXT PRIMARY KEY,
    app_challenge   TEXT NOT NULL,
    subject         TEXT NOT NULL,
    display_name    TEXT,
    is_admin        BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Long-lived app sessions (tokens stored hashed; expiry slides on use).
CREATE TABLE IF NOT EXISTS device_sessions (
    token_hash      TEXT PRIMARY KEY,
    subject         TEXT NOT NULL,
    display_name    TEXT,
    is_admin        BOOLEAN NOT NULL DEFAULT false,
    user_agent      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS device_sessions_subject_idx ON device_sessions (subject);
"""


class Database:
    def __init__(self, url: str) -> None:
        self.pool = ConnectionPool(url, min_size=1, max_size=8, kwargs={"row_factory": dict_row}, open=False)

    def open(self) -> None:
        self.pool.open(wait=True, timeout=30)
        with self.pool.connection() as conn:
            conn.execute(SCHEMA)
            conn.execute(VOICE_SCHEMA)

    def close(self) -> None:
        self.pool.close()

    @contextmanager
    def connection(self):
        with self.pool.connection() as conn:
            yield conn
