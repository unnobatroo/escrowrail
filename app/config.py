"""Application configuration via environment variables."""

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Settings:
    """Runtime settings. Defaults are local-dev / test friendly."""

    database_url: str = field(
        default_factory=lambda: os.environ.get("WISE_DATABASE_URL", "sqlite:///./wise_escrow.db")
    )
    github_webhook_secret: str | None = field(
        default_factory=lambda: os.environ.get("WISE_GITHUB_WEBHOOK_SECRET") or None
    )
    frankfurter_url: str = field(
        default_factory=lambda: os.environ.get(
            "WISE_FRANKFURTER_URL", "https://api.frankfurter.dev/v1"
        )
    )
    fx_live_enabled: bool = field(
        default_factory=lambda: os.environ.get("WISE_FX_LIVE", "").lower() in ("1", "true", "yes")
    )
    fx_timeout_seconds: float = field(
        default_factory=lambda: float(os.environ.get("WISE_FX_TIMEOUT", "2.0"))
    )


settings = Settings()
