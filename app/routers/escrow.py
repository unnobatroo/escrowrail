"""Escrow contract endpoints. Thin controllers only."""

import uuid
from decimal import Decimal
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from app.dependencies import get_escrow_repo, get_escrow_service
from app.models.escrow import EscrowContract, TrackingType
from app.models.ledger import LedgerTransaction
from app.repositories.escrow_repository import EscrowRepository
from app.services.escrow_service import EscrowService

router = APIRouter(prefix="/contracts", tags=["escrow"])


class CreateContractRequest(BaseModel):
    buyer_id: uuid.UUID
    seller_id: uuid.UUID
    target_currency: str = Field(min_length=3, max_length=3)
    target_amount: Decimal = Field(gt=0)
    source_currency: str = Field(min_length=3, max_length=3)
    tracking_type: TrackingType
    tracking_key: str


class FundContractRequest(BaseModel):
    account_id: uuid.UUID
    deposit_amount: Decimal = Field(gt=0)


EscrowServiceDep = Annotated[EscrowService, Depends(get_escrow_service)]


@router.post("", status_code=201)
def create_contract(body: CreateContractRequest, service: EscrowServiceDep) -> EscrowContract:
    return service.create_contract(
        buyer_id=body.buyer_id,
        seller_id=body.seller_id,
        target_currency=body.target_currency,
        target_amount=body.target_amount,
        source_currency=body.source_currency,
        tracking_type=body.tracking_type,
        tracking_key=body.tracking_key,
    )


@router.get("/{contract_id}")
def get_contract(contract_id: uuid.UUID, service: EscrowServiceDep) -> EscrowContract:
    return service.get_contract(contract_id)


@router.get("/{contract_id}/ledger")
def get_contract_ledger(
    contract_id: uuid.UUID,
    contracts: Annotated[EscrowRepository, Depends(get_escrow_repo)],
) -> list[LedgerTransaction]:
    return contracts.ledger_entries(contract_id)


@router.post("/{contract_id}/fund")
def fund_contract(
    contract_id: uuid.UUID,
    body: FundContractRequest,
    service: EscrowServiceDep,
) -> EscrowContract:
    return service.fund_contract(contract_id, body.account_id, body.deposit_amount)


@router.post("/{contract_id}/cancel")
def cancel_contract(contract_id: uuid.UUID, service: EscrowServiceDep) -> EscrowContract:
    return service.cancel_contract(contract_id)
