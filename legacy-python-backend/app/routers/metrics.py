"""Prometheus scrape endpoint. Refreshes the ledger invariant gauge
before rendering so the metric reflects live state."""

from fastapi import APIRouter
from prometheus_client import generate_latest
from starlette.responses import Response

from app.dependencies import SessionDep
from app.middleware.metrics import CONTENT_TYPE_LATEST, LEDGER_DISCREPANCY_CENTS
from app.services.invariant_service import compute_discrepancy_cents

router = APIRouter(tags=["metrics"])


@router.get("/metrics")
def metrics(session: SessionDep) -> Response:
    LEDGER_DISCREPANCY_CENTS.set(compute_discrepancy_cents(session))
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)
