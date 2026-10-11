"""Spot FX rate service.

Implements ``IFXRateProvider`` so tests can inject a stub provider.
Rates are pivoted through USD: 1 EUR = 1.08 USD, 1 GBP = 1.25 USD.
When live mode is enabled, rates are fetched from the Frankfurter API
with a static-table fallback for offline operation.
"""

import logging
from decimal import Decimal
from typing import Protocol

import httpx

from app.config import settings
from app.exceptions import UnsupportedCurrencyPairError

logger = logging.getLogger("escrowrail_escrow.fx")

# USD value of one unit of each supported currency (static offline table).
STATIC_USD_VALUES: dict[str, Decimal] = {
    "USD": Decimal("1"),
    "EUR": Decimal("1.08"),
    "GBP": Decimal("1.25"),
}


class IFXRateProvider(Protocol):
    """Dependency-inversion contract for FX rate lookups."""

    def get_rate(self, from_currency: str, to_currency: str) -> Decimal:
        """Return the multiplier converting 1 unit of ``from_currency``
        into ``to_currency``. Exact Decimal, never float."""
        ...


class FXService:
    """Default rate provider: optional Frankfurter fetch, static fallback."""

    def __init__(
        self,
        live_enabled: bool = False,
        frankfurter_url: str = settings.frankfurter_url,
        timeout_seconds: float = settings.fx_timeout_seconds,
    ) -> None:
        self.live_enabled = live_enabled
        self.frankfurter_url = frankfurter_url
        self.timeout_seconds = timeout_seconds

    def get_rate(self, from_currency: str, to_currency: str) -> Decimal:
        from_currency = from_currency.upper()
        to_currency = to_currency.upper()
        if from_currency == to_currency:
            return Decimal("1")
        self._require_pair(from_currency, to_currency)
        if self.live_enabled:
            rate = self._fetch_live_rate(from_currency, to_currency)
            if rate is not None:
                return rate
        return self._static_rate(from_currency, to_currency)

    def _require_pair(self, from_currency: str, to_currency: str) -> None:
        missing = [c for c in (from_currency, to_currency) if c not in STATIC_USD_VALUES]
        if missing:
            raise UnsupportedCurrencyPairError(
                f"Unsupported currency pair {from_currency}->{to_currency}",
                context={
                    "from_currency": from_currency,
                    "to_currency": to_currency,
                    "missing": missing,
                },
            )

    def _static_rate(self, from_currency: str, to_currency: str) -> Decimal:
        return STATIC_USD_VALUES[from_currency] / STATIC_USD_VALUES[to_currency]

    def _fetch_live_rate(self, from_currency: str, to_currency: str) -> Decimal | None:
        try:
            response = httpx.get(
                f"{self.frankfurter_url}/latest",
                params={"base": from_currency, "symbols": to_currency},
                timeout=self.timeout_seconds,
            )
            response.raise_for_status()
            raw = response.json()["rates"][to_currency]
            rate = Decimal(str(raw))
        except (httpx.HTTPError, KeyError, ValueError) as exc:
            logger.warning(
                "frankfurter_fetch_failed",
                extra={
                    "from_currency": from_currency,
                    "to_currency": to_currency,
                    "error": str(exc),
                },
            )
            return None
        if rate <= 0:
            return None
        return rate
