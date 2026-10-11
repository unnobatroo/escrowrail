"""Tenant-scoped escrow contract endpoints under /api/v1. Thin controllers."""

import uuid
from datetime import datetime
from decimal import ROUND_CEILING, Decimal
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from app.dependencies import (
    CurrentUser,
    TaskQueueDep,
    get_entity_repo,
    get_escrow_service,
    get_user_repo,
)
from app.exceptions import NotFoundError
from app.middleware.metrics import CONTRACTS_CANCELLED, CONTRACTS_CREATED
from app.models.escrow import EscrowContract, TrackingType
from app.models.ledger import LedgerTransaction
from app.repositories.account_repository import EntityRepository
from app.repositories.user_repository import UserRepository
from app.services.escrow_service import EscrowService
from app.services.ledger_service import CENT
from app.tasks import send_counterparty_invite

router = APIRouter(prefix="/api/v1/escrow", tags=["escrow"])

EscrowServiceDep = Annotated[EscrowService, Depends(get_escrow_service)]
EntityRepoDep = Annotated[EntityRepository, Depends(get_entity_repo)]
UserRepoDep = Annotated[UserRepository, Depends(get_user_repo)]


class CreateContractRequest(BaseModel):
    counterparty_email: str = Field(min_length=3, max_length=254)
    target_amount: Decimal = Field(gt=0)
    tracking_type: TrackingType
    tracking_key: str = Field(min_length=1, max_length=500)
    target_currency: str = Field(default="EUR", min_length=3, max_length=3)
    source_currency: str = Field(default="USD", min_length=3, max_length=3)


class FundContractRequest(BaseModel):
    deposit_amount: Decimal = Field(gt=0)


class ContractView(BaseModel):
    id: uuid.UUID
    buyer_id: uuid.UUID
    seller_id: uuid.UUID
    buyer_name: str
    seller_name: str
    role: Literal["buyer", "seller"]
    status: str
    target_currency: str
    target_amount: Decimal
    source_currency: str
    deposit_amount: Decimal | None
    locked_spot_rate: Decimal | None
    escrowed_amount: Decimal | None
    tracking_type: TrackingType
    tracking_key: str
    funded_at: datetime | None
    created_at: datetime


class QuoteResponse(BaseModel):
    source_currency: str
    target_currency: str
    target_amount: Decimal
    fx_rate: Decimal
    display_rate: Decimal
    principal: Decimal
    fx_fee: Decimal
    total_deposit: Decimal


class CounterpartyView(BaseModel):
    email: str
    entity_id: uuid.UUID
    legal_name: str
    ubo_tax_id: str
    country: str


def _view(contract: EscrowContract, entities: EntityRepository, me_id: uuid.UUID) -> ContractView:
    buyer = entities.get(contract.buyer_id)
    seller = entities.get(contract.seller_id)
    return ContractView(
        id=contract.id,
        buyer_id=contract.buyer_id,
        seller_id=contract.seller_id,
        buyer_name=buyer.legal_name if buyer else "",
        seller_name=seller.legal_name if seller else "",
        role="buyer" if contract.buyer_id == me_id else "seller",
        status=contract.status.value,
        target_currency=contract.target_currency,
        target_amount=contract.target_amount,
        source_currency=contract.source_currency,
        deposit_amount=contract.deposit_amount,
        locked_spot_rate=contract.locked_spot_rate,
        escrowed_amount=contract.escrowed_amount,
        tracking_type=contract.tracking_type,
        tracking_key=contract.tracking_key,
        funded_at=contract.funded_at,
        created_at=contract.created_at,
    )


@router.get("/quote")
def quote(
    _user: CurrentUser,
    service: EscrowServiceDep,
    target_amount: Decimal = Query(gt=0),
    target_currency: str = Query(default="EUR", min_length=3, max_length=3),
    source_currency: str = Query(default="USD", min_length=3, max_length=3),
) -> QuoteResponse:
    """Authoritative deposit quote: smallest source deposit whose converted
    amount still covers the target after the 0.5% conversion fee."""
    rate = service.fx.get_rate(source_currency.upper(), target_currency.upper())
    one = Decimal("1") - EscrowService.CONVERSION_FEE_RATE
    total = (target_amount / (one * rate)).quantize(CENT, ROUND_CEILING)
    fee = (total * EscrowService.CONVERSION_FEE_RATE).quantize(CENT)
    if (total - fee) * rate < target_amount:
        total += CENT
        fee = (total * EscrowService.CONVERSION_FEE_RATE).quantize(CENT)
    display = service.fx.get_rate(target_currency.upper(), source_currency.upper())
    return QuoteResponse(
        source_currency=source_currency.upper(),
        target_currency=target_currency.upper(),
        target_amount=target_amount,
        fx_rate=rate,
        display_rate=display,
        principal=total - fee,
        fx_fee=fee,
        total_deposit=total,
    )


@router.get("/counterparty")
def resolve_counterparty(
    email: str,
    user: CurrentUser,
    users: UserRepoDep,
    entities: EntityRepoDep,
) -> CounterpartyView:
    counterparty = users.find_by_email(email)
    if counterparty is None:
        raise NotFoundError(
            "No registered organization for this email",
            context={"email": email},
        )
    entity = entities.get(counterparty.entity_id)
    if entity is None:
        raise NotFoundError("Counterparty entity not found", context={})
    return CounterpartyView(
        email=counterparty.email,
        entity_id=entity.id,
        legal_name=entity.legal_name,
        ubo_tax_id=entity.ubo_tax_id,
        country=entity.country,
    )


@router.get("")
def list_contracts(
    user: CurrentUser,
    service: EscrowServiceDep,
    entities: EntityRepoDep,
) -> list[ContractView]:
    return [
        _view(c, entities, user.entity_id)
        for c in service.list_contracts_for_entity(user.entity_id)
    ]


@router.post("", status_code=201)
def create_contract(
    body: CreateContractRequest,
    user: CurrentUser,
    service: EscrowServiceDep,
    users: UserRepoDep,
    entities: EntityRepoDep,
    tasks: TaskQueueDep,
) -> ContractView:
    counterparty = users.find_by_email(body.counterparty_email)
    if counterparty is None:
        # External invite: queue IO outside the transaction path.
        buyer_entity = entities.get(user.entity_id)
        tasks.enqueue(
            send_counterparty_invite,
            body.counterparty_email,
            buyer_entity.legal_name if buyer_entity else "",
        )
        raise NotFoundError(
            "Counterparty is not on Wise Smart Escrow yet — an invite was sent",
            context={"email": body.counterparty_email},
        )
    contract = service.create_contract(
        buyer_id=user.entity_id,
        seller_id=counterparty.entity_id,
        target_currency=body.target_currency,
        target_amount=body.target_amount,
        source_currency=body.source_currency,
        tracking_type=body.tracking_type,
        tracking_key=body.tracking_key,
    )
    CONTRACTS_CREATED.inc()
    return _view(contract, entities, user.entity_id)


@router.get("/{contract_id}")
def get_contract(
    contract_id: uuid.UUID,
    user: CurrentUser,
    service: EscrowServiceDep,
    entities: EntityRepoDep,
) -> ContractView:
    contract = service.get_contract_for_user(contract_id, user.entity_id)
    return _view(contract, entities, user.entity_id)


@router.get("/{contract_id}/audit")
def get_contract_audit(
    contract_id: uuid.UUID,
    user: CurrentUser,
    service: EscrowServiceDep,
) -> list[LedgerTransaction]:
    return service.contract_audit(contract_id, user.entity_id)


@router.post("/{contract_id}/fund")
def fund_contract(
    contract_id: uuid.UUID,
    body: FundContractRequest,
    user: CurrentUser,
    service: EscrowServiceDep,
    entities: EntityRepoDep,
) -> ContractView:
    funded = service.fund_contract(contract_id, user.entity_id, None, body.deposit_amount)
    return _view(funded, entities, user.entity_id)


@router.post("/{contract_id}/cancel")
def cancel_contract(
    contract_id: uuid.UUID,
    user: CurrentUser,
    service: EscrowServiceDep,
    entities: EntityRepoDep,
) -> ContractView:
    cancelled = service.cancel_contract(contract_id, user.entity_id)
    CONTRACTS_CANCELLED.inc()
    return _view(cancelled, entities, user.entity_id)
