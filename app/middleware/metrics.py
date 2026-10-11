"""Prometheus metrics registry and HTTP latency instrumentation."""

import re
import time

from prometheus_client import CONTENT_TYPE_LATEST, Counter, Gauge, Histogram
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

CONTRACTS_CREATED = Counter("escrow_contracts_created_total", "Escrow contracts created")
CONTRACTS_SETTLED = Counter("escrow_contracts_settled_total", "Escrow contracts settled")
CONTRACTS_CANCELLED = Counter("escrow_contracts_cancelled_total", "Escrow contracts cancelled")
LEDGER_DISCREPANCY_CENTS = Gauge(
    "ledger_invariant_discrepancy_cents",
    "Abs cent discrepancy between balances and ledger replay (must be 0)",
)
HTTP_DURATION = Histogram(
    "http_request_duration_seconds",
    "HTTP request latency",
    ["method", "path"],
)

_UUID_RE = re.compile(
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
)


class MetricsMiddleware(BaseHTTPMiddleware):
    """Records request latency into http_request_duration_seconds with
    cardinality-safe path labels (UUID path params normalized)."""

    async def dispatch(self, request: Request, call_next: object) -> Response:
        start = time.perf_counter()
        response: Response = await call_next(request)  # type: ignore[operator]
        path = _UUID_RE.sub("{id}", request.url.path)
        HTTP_DURATION.labels(request.method, path).observe(time.perf_counter() - start)
        return response


__all__ = [
    "CONTENT_TYPE_LATEST",
    "CONTRACTS_CANCELLED",
    "CONTRACTS_CREATED",
    "CONTRACTS_SETTLED",
    "HTTP_DURATION",
    "LEDGER_DISCREPANCY_CENTS",
    "MetricsMiddleware",
]
