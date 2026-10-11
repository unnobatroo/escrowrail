"""Application configuration via environment variables.

Loads ``.env`` from the project root if present (no dependency needed),
then falls back to real environment variables, then to safe defaults.
"""

import os
from dataclasses import dataclass, field
from pathlib import Path


def _load_dotenv(path: Path) -> dict[str, str]:
    """Minimal .env parser: KEY=VALUE lines, '#' comments, no quoting rules."""
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


_DOTENV = _load_dotenv(Path(__file__).resolve().parent.parent / ".env")


def _env(key: str, default: str = "") -> str:
    return os.environ.get(key) or _DOTENV.get(key) or default


@dataclass(frozen=True)
class Settings:
    """Runtime settings. Defaults are local-dev / test friendly."""

    database_url: str = field(
        default_factory=lambda: _env(
            "DATABASE_URL", _env("WISE_DATABASE_URL", "sqlite:///./wise_escrow.db")
        )
    )
    log_level: str = field(default_factory=lambda: _env("LOG_LEVEL", "INFO"))
    github_webhook_secret: str | None = field(
        default_factory=lambda: _env("WISE_GITHUB_WEBHOOK_SECRET") or None
    )
    logistics_webhook_secret: str | None = field(
        default_factory=lambda: _env("WISE_LOGISTICS_WEBHOOK_SECRET") or None
    )
    frankfurter_url: str = field(
        default_factory=lambda: _env("WISE_FRANKFURTER_URL", "https://api.frankfurter.dev/v1")
    )
    fx_live_enabled: bool = field(
        default_factory=lambda: _env("WISE_FX_LIVE").lower() in ("1", "true", "yes")
    )
    fx_timeout_seconds: float = field(default_factory=lambda: float(_env("WISE_FX_TIMEOUT", "2.0")))
    # Informational ECB reference rate (%) — reserved for yield/disclosure
    # features; not used in settlement math.
    ecb_base_rate: str = field(default_factory=lambda: _env("ECB_BASE_RATE", "4.50"))
    jwt_secret: str = field(default_factory=lambda: _env("JWT_SECRET"))
    jwt_algorithm: str = field(default_factory=lambda: _env("JWT_ALGORITHM", "HS256"))
    jwt_expire_minutes: int = field(default_factory=lambda: int(_env("JWT_EXPIRE_MINUTES", "120")))

    def __post_init__(self) -> None:
        # The dev fallback is a public string — acceptable only where the
        # whole deployment is throwaway anyway (local SQLite / tests).
        if not self.jwt_secret:
            if not self.database_url.startswith("sqlite"):
                raise RuntimeError("JWT_SECRET must be set for non-SQLite deployments")
            object.__setattr__(self, "jwt_secret", "dev-only-insecure-secret-change-me")
        # Settlement webhooks release real funds: without shared secrets
        # anyone can forge a milestone event. SQLite stays open for the
        # dev "simulate" path and the local test suite.
        if not self.database_url.startswith("sqlite"):
            missing = [
                name
                for name, value in (
                    ("WISE_GITHUB_WEBHOOK_SECRET", self.github_webhook_secret),
                    ("WISE_LOGISTICS_WEBHOOK_SECRET", self.logistics_webhook_secret),
                )
                if not value
            ]
            if missing:
                raise RuntimeError(f"{', '.join(missing)} must be set for non-SQLite deployments")


settings = Settings()
