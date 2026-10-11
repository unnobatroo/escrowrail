"""Tenant-scoped account and entity endpoints under /api/v1."""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlmodel import select

from app.dependencies import (
    CurrentUser,
    SessionDep,
    get_account_repo,
    get_entity_repo,
)
from app.exceptions import NotFoundError
from app.models.entities import Account, AccountType, Entity
from app.models.ledger import LedgerTransaction
from app.repositories.account_repository import AccountRepository, EntityRepository

router = APIRouter(prefix="/api/v1", tags=["accounts"])

AccountRepoDep = Annotated[AccountRepository, Depends(get_account_repo)]
EntityRepoDep = Annotated[EntityRepository, Depends(get_entity_repo)]


@router.get("/entities/me")
def get_my_entity(user: CurrentUser, entities: EntityRepoDep) -> Entity:
    entity = entities.get(user.entity_id)
    if entity is None:
        raise NotFoundError("Linked entity not found", context={"entity_id": str(user.entity_id)})
    return entity


@router.get("/accounts")
def list_my_accounts(user: CurrentUser, session: SessionDep) -> list[Account]:
    stmt = (
        select(Account)
        .where(
            Account.entity_id == user.entity_id,
            Account.account_type == AccountType.USER_ACCOUNT,
        )
        .order_by(Account.currency)
    )
    return list(session.exec(stmt).all())


@router.get("/accounts/{account_id}")
def get_account(account_id: uuid.UUID, user: CurrentUser, accounts: AccountRepoDep) -> Account:
    account = accounts.get(account_id)
    if account is None or account.entity_id != user.entity_id:
        raise NotFoundError("Account not found", context={"account_id": str(account_id)})
    return account


@router.get("/accounts/{account_id}/ledger")
def get_account_ledger(
    account_id: uuid.UUID,
    user: CurrentUser,
    session: SessionDep,
    accounts: AccountRepoDep,
) -> list[LedgerTransaction]:
    account = accounts.get(account_id)
    if account is None or account.entity_id != user.entity_id:
        raise NotFoundError("Account not found", context={"account_id": str(account_id)})
    stmt = (
        select(LedgerTransaction)
        .where(
            (LedgerTransaction.debit_account_id == account_id)
            | (LedgerTransaction.credit_account_id == account_id)
        )
        .order_by(LedgerTransaction.created_at)  # type: ignore[arg-type]
    )
    return list(session.exec(stmt).all())
