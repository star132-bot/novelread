"""Runtime settings, all taken from environment variables (see server/.env.example)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _list(name: str) -> list[str]:
    return [item.strip() for item in os.environ.get(name, "").split(",") if item.strip()]


@dataclass(frozen=True)
class Settings:
    database_url: str = field(default_factory=lambda: os.environ["DATABASE_URL"])
    data_dir: Path = field(default_factory=lambda: Path(os.environ.get("DATA_DIR", "/data")))
    # Public HTTPS origin of this server, e.g. https://books.mkauth.sbs (used for the OIDC callback).
    public_base_url: str = field(default_factory=lambda: os.environ.get("PUBLIC_BASE_URL", "").rstrip("/"))
    # MKauth: this server is a confidential OIDC client; the app never sees MKauth tokens.
    oidc_issuer: str = field(default_factory=lambda: os.environ.get("OIDC_ISSUER", "https://auth.mkauth.sbs").rstrip("/"))
    oidc_client_id: str = field(default_factory=lambda: os.environ.get("OIDC_CLIENT_ID", ""))
    oidc_client_secret: str = field(default_factory=lambda: os.environ.get("OIDC_CLIENT_SECRET", ""))
    oidc_scopes: str = field(default_factory=lambda: os.environ.get("OIDC_SCOPES", "openid profile email"))
    # "login": every request needs a device session; "public": catalog/downloads are open.
    read_access: str = field(default_factory=lambda: os.environ.get("READ_ACCESS", "login"))
    # MKauth subject ids allowed to publish books.
    admin_subjects: list[str] = field(default_factory=lambda: _list("ADMIN_SUBJECTS"))
    # Long random token for publishing from scripts or the web admin page.
    admin_api_token: str = field(default_factory=lambda: os.environ.get("ADMIN_API_TOKEN", ""))
    # Trusted platforms that call the admin API for a signed-in person of theirs (e.g. Server Hub):
    # "name:role:token" entries, comma separated. Requests must name the person in X-Admin-Actor.
    admin_service_tokens: list[str] = field(default_factory=lambda: _list("ADMIN_SERVICE_TOKENS"))
    # When set, /admin redirects here (the MKread module in Server Hub); the admin API stays available.
    admin_console_url: str = field(default_factory=lambda: os.environ.get("ADMIN_CONSOLE_URL", "").strip())
    # Origins allowed to show the admin console in an iframe, e.g. https://hub.mkauth.sbs.
    admin_embed_origins: list[str] = field(default_factory=lambda: _list("ADMIN_EMBED_ORIGINS"))
    # Mainland-China copy of APKs and voice packs, e.g. https://<bucket>.oss-cn-shanghai.aliyuncs.com
    # (see app/mirror.py); downloads fall back to this server when a file is not there.
    download_mirror_url: str = field(default_factory=lambda: os.environ.get("DOWNLOAD_MIRROR_URL", "").strip().rstrip("/"))
    session_days: int = field(default_factory=lambda: int(os.environ.get("SESSION_DAYS", "180")))
    max_upload_bytes: int = field(default_factory=lambda: int(os.environ.get("MAX_UPLOAD_MB", "200")) * 1024 * 1024)

    def __post_init__(self) -> None:
        if self.read_access not in {"login", "public"}:
            raise ValueError("READ_ACCESS must be 'login' or 'public'")
        if self.admin_api_token and len(self.admin_api_token) < 32:
            raise ValueError("ADMIN_API_TOKEN must be at least 32 characters")
        for origin in self.admin_embed_origins:
            local = origin.startswith(("http://localhost:", "http://127.0.0.1:"))
            if not (origin.startswith("https://") or local) or any(c in origin for c in " ;'\"*"):
                raise ValueError("ADMIN_EMBED_ORIGINS entries must be https:// origins (or local http for development)")
        if self.download_mirror_url and not self.download_mirror_url.startswith("https://"):
            raise ValueError("DOWNLOAD_MIRROR_URL must be an https:// URL")
        for entry in self.admin_service_tokens:
            name, role, token = (entry.split(":", 2) + ["", ""])[:3]
            if not name or role not in {"viewer", "operator", "superadmin"} or len(token) < 32:
                raise ValueError("ADMIN_SERVICE_TOKENS entries must be name:viewer|operator|superadmin:<32+ char token>")

    @property
    def service_tokens(self) -> dict[str, tuple[str, str]]:
        """token → (service name, role)."""
        return {token: (name, role) for name, role, token in (e.split(":", 2) for e in self.admin_service_tokens)}

    @property
    def login_enabled(self) -> bool:
        return bool(self.public_base_url and self.oidc_client_id and self.oidc_client_secret)
