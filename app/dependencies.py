"""Dependency-injection providers for FastAPI ``Depends``.

Routers depend on these providers; concrete services are never
instantiated inside route handlers. Tests override ``get_session``
(and optionally ``get_fx_provider``) to isolate the stack.
"""

from typing import Annotated

from fastapi import Depends
from sqlmodel import Session

from app.config import settings
from app.database import get_session
from app.repositories.account_repository import AccountRepository, EntityRepository
from app.repositories.escrow_repository import EscrowRepository
from app.services.escrow_service import EscrowService
from app.services.fx_service import FXService, IFXRateProvider
from app.services.ledger_service import LedgerService

SessionDep = Annotated[Session, Depends(get_session)]


def get_entity_repo(session: SessionDep) -> EntityRepository:
    return EntityRepository(session)


def get_account_repo(session: SessionDep) -> AccountRepository:
    return AccountRepository(session)


def get_escrow_repo(session: SessionDep) -> EscrowRepository:
    return EscrowRepository(session)


def get_fx_provider() -> IFXRateProvider:
    return FXService(live_enabled=settings.fx_live_enabled)


def get_ledger_service(
    session: SessionDep,
    accounts: Annotated[AccountRepository, Depends(get_account_repo)],
) -> LedgerService:
    return LedgerService(session, accounts)


def get_escrow_service(
    session: SessionDep,
    entities: Annotated[EntityRepository, Depends(get_entity_repo)],
    accounts: Annotated[AccountRepository, Depends(get_account_repo)],
    contracts: Annotated[EscrowRepository, Depends(get_escrow_repo)],
    ledger: Annotated[LedgerService, Depends(get_ledger_service)],
    fx: Annotated[IFXRateProvider, Depends(get_fx_provider)],
) -> EscrowService:
    return EscrowService(session, entities, accounts, contracts, ledger, fx)
