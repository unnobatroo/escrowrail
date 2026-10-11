"""Programmatic release webhooks.

Funds only transition FUNDED -> SETTLED through these validated events:
- GitHub PR merge (action == "closed", merged == true)
- Logistics carrier delivery (carrier == "DHL", status == "DELIVERED")
"""

import hashlib
import hmac
import json
import uuid
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel

from app.config import settings
from app.dependencies import get_escrow_repo, get_escrow_service
from app.exceptions import WebhookValidationError
from app.middleware.metrics import CONTRACTS_SETTLED
from app.models.escrow import TrackingType
from app.repositories.escrow_repository import EscrowRepository
from app.services.escrow_service import EscrowService

router = APIRouter(prefix="/webhooks", tags=["webhooks"])

EscrowServiceDep = Annotated[EscrowService, Depends(get_escrow_service)]
EscrowRepoDep = Annotated[EscrowRepository, Depends(get_escrow_repo)]


class LogisticsWebhook(BaseModel):
    carrier: str
    status: str
    tracking_key: str


class WebhookResult(BaseModel):
    status: str
    contract_id: uuid.UUID | None = None


def _verify_github_signature(body: bytes, signature: str | None) -> None:
    secret = settings.github_webhook_secret
    if secret is None:
        return
    if not signature or not signature.startswith("sha256="):
        raise WebhookValidationError("Missing or malformed GitHub signature")
    expected = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(f"sha256={expected}", signature):
        raise WebhookValidationError("Invalid GitHub webhook signature")


@router.post("/github")
async def github_webhook(
    request: Request, service: EscrowServiceDep, contracts: EscrowRepoDep
) -> WebhookResult:
    body = await request.body()
    _verify_github_signature(body, request.headers.get("X-Hub-Signature-256"))
    try:
        payload: dict[str, Any] = json.loads(body)
    except json.JSONDecodeError as exc:
        raise WebhookValidationError("Invalid JSON payload") from exc

    pull_request = payload.get("pull_request")
    tracking_key = payload.get("tracking_key")
    if tracking_key is None and isinstance(pull_request, dict):
        tracking_key = pull_request.get("html_url") or pull_request.get("url")

    merged = bool(isinstance(pull_request, dict) and pull_request.get("merged") is True)
    if not (payload.get("action") == "closed" and merged and tracking_key is not None):
        return WebhookResult(status="ignored")

    contract = contracts.find_funded_by_tracking(TrackingType.GITHUB_PR, str(tracking_key))
    if contract is None:
        return WebhookResult(status="ignored")
    settled = service.settle_milestone(contract.id)
    CONTRACTS_SETTLED.inc()
    return WebhookResult(status="settled", contract_id=settled.id)


@router.post("/logistics")
def logistics_webhook(
    body: LogisticsWebhook, service: EscrowServiceDep, contracts: EscrowRepoDep
) -> WebhookResult:
    if body.carrier != "DHL":
        raise WebhookValidationError(
            "Unsupported carrier",
            context={"carrier": body.carrier},
        )
    if body.status != "DELIVERED":
        return WebhookResult(status="ignored")
    contract = contracts.find_funded_by_tracking(TrackingType.SHIPPING_CARRIER, body.tracking_key)
    if contract is None:
        return WebhookResult(status="ignored")
    settled = service.settle_milestone(contract.id)
    return WebhookResult(status="settled", contract_id=settled.id)
