"""Health probes: fast liveness and a readiness check that verifies
database connectivity plus the ledger conservation invariant."""

from fastapi import APIRouter
from pydantic import BaseModel
from sqlalchemy import text

from app.dependencies import SessionDep
from app.services.invariant_service import compute_discrepancy_cents

router = APIRouter(prefix="/health", tags=["health"])


class LiveResponse(BaseModel):
    status: str


class ReadyResponse(BaseModel):
    status: str
    ledger_balanced: bool
    discrepancy_cents: int


@router.get("/live")
def live() -> LiveResponse:
    return LiveResponse(status="alive")


@router.get("/ready")
def ready(session: SessionDep) -> ReadyResponse:
    session.connection().execute(text("SELECT 1"))
    discrepancy = compute_discrepancy_cents(session)
    return ReadyResponse(
        status="ready" if discrepancy == 0 else "degraded",
        ledger_balanced=discrepancy == 0,
        discrepancy_cents=discrepancy,
    )
