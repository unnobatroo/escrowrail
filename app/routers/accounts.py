"""Entity and account endpoints. Thin controllers only."""

import uuid
from decimal import Decimal
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlmodel import select

from app.dependencies import (
    SessionDep,
    get_account_repo,
    get_entity_repo,
    get_ledger_service,
)
from app.exceptions import CurrencyMismatchError, NotFoundError
from app.models.entities import Account, AccountType, Entity
from app.models.ledger import LedgerTransaction
from app.repositories.account_repository import AccountRepository, EntityRepository
from app.services.ledger_service import LedgerService

router = APIRouter(tags=["accounts"])


class CreateEntityRequest(BaseModel):
    legal_name: str
    ubo_tax_id: str
    country: str


class CreateAccountRequest(BaseModel):
    entity_id: uuid.UUID
    currency: str = Field(min_length=3, max_length=3)
    initial_balance: Decimal = Field(default=Decimal("0"), ge=0)


@router.post("/entities", status_code=201)
def create_entity(
    body: CreateEntityRequest,
    entities: Annotated[EntityRepository, Depends(get_entity_repo)],
) -> Entity:
    return entities.create(
        Entity(
            legal_name=body.legal_name,
            ubo_tax_id=body.ubo_tax_id,
            country=body.country,
        )
    )


@router.get("/entities/{entity_id}")
def get_entity(
    entity_id: uuid.UUID,
    entities: Annotated[EntityRepository, Depends(get_entity_repo)],
) -> Entity:
    entity = entities.get(entity_id)
    if entity is None:
        raise NotFoundError("Entity not found", context={"entity_id": str(entity_id)})
    return entity


@router.post("/accounts", status_code=201)
def create_account(
    body: CreateAccountRequest,
    session: SessionDep,
    accounts: Annotated[AccountRepository, Depends(get_account_repo)],
    entities: Annotated[EntityRepository, Depends(get_entity_repo)],
    ledger: Annotated[LedgerService, Depends(get_ledger_service)],
) -> Account:
    if entities.get(body.entity_id) is None:
        raise NotFoundError("Entity not found", context={"entity_id": str(body.entity_id)})
    currency = body.currency.upper()
    if accounts.find_entity_account(body.entity_id, currency) is not None:
        raise CurrencyMismatchError(
            "Entity already has an account in this currency",
            context={"entity_id": str(body.entity_id), "currency": currency},
        )
    account = accounts.create(
        Account(
            entity_id=body.entity_id,
            account_type=AccountType.USER_ACCOUNT,
            currency=currency,
        )
    )
    if body.initial_balance > 0:
        clearing = accounts.get_or_create_system_account(
            AccountType.WISE_CLEARING_ACCOUNT, currency
        )
        ledger.record_transfer(
            clearing.id,
            account.id,
            body.initial_balance,
            currency,
            "Initial account funding",
        )
    session.refresh(account)
    return account


@router.get("/accounts/{account_id}")
def get_account(
    account_id: uuid.UUID,
    accounts: Annotated[AccountRepository, Depends(get_account_repo)],
) -> Account:
    account = accounts.get(account_id)
    if account is None:
        raise NotFoundError("Account not found", context={"account_id": str(account_id)})
    return account


@router.get("/accounts/{account_id}/ledger")
def get_account_ledger(account_id: uuid.UUID, session: SessionDep) -> list[LedgerTransaction]:
    stmt = (
        select(LedgerTransaction)
        .where(
            (LedgerTransaction.debit_account_id == account_id)
            | (LedgerTransaction.credit_account_id == account_id)
        )
        .order_by(LedgerTransaction.created_at)  # type: ignore[arg-type]
    )
    return list(session.exec(stmt).all())
