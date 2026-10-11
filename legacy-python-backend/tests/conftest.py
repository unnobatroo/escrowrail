"""Shared fixtures: isolated in-memory DB, stub FX, TestClient wiring."""

import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.pool import StaticPool
from sqlmodel import Session, SQLModel, create_engine

from app.database import get_session
from app.dependencies import get_fx_provider
from app.main import app
from app.models.auth import User
from app.models.entities import Account, AccountType, Entity
from app.repositories.account_repository import AccountRepository, EntityRepository
from app.repositories.escrow_repository import EscrowRepository
from app.repositories.user_repository import UserRepository
from app.services.auth_service import AuthService
from app.services.escrow_service import EscrowService
from app.services.fx_service import IFXRateProvider
from app.services.ledger_service import LedgerService


class StubFXProvider:
    """Deterministic FX provider for tests. USD pivot: EUR=1.08, GBP=1.25."""

    def __init__(self) -> None:
        self.usd_values = {
            "USD": Decimal("1"),
            "EUR": Decimal("1.08"),
            "GBP": Decimal("1.25"),
        }

    def get_rate(self, from_currency: str, to_currency: str) -> Decimal:
        if from_currency == to_currency:
            return Decimal("1")
        return self.usd_values[from_currency] / self.usd_values[to_currency]


@pytest.fixture(name="session")
def session_fixture() -> Iterator[Session]:
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    SQLModel.metadata.create_all(engine)
    with Session(engine) as session:
        yield session


@pytest.fixture(name="fx")
def fx_fixture() -> StubFXProvider:
    return StubFXProvider()


@pytest.fixture(name="escrow_service")
def escrow_service_fixture(session: Session, fx: IFXRateProvider) -> EscrowService:
    accounts = AccountRepository(session)
    return EscrowService(
        session,
        EntityRepository(session),
        accounts,
        EscrowRepository(session),
        LedgerService(session, accounts),
        fx,
    )


@pytest.fixture(name="ledger")
def ledger_fixture(session: Session) -> LedgerService:
    return LedgerService(session, AccountRepository(session))


@contextmanager
def _wired_client(session: Session, fx: IFXRateProvider) -> Iterator[TestClient]:
    def override_session() -> Iterator[Session]:
        yield session

    def override_fx() -> IFXRateProvider:
        return fx

    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_fx_provider] = override_fx
    # Route middleware storage (idempotency, delivery logs) at the same
    # test database instead of the module-level dev engine.
    previous_engine = app.state.engine
    app.state.engine = session.get_bind()
    try:
        with TestClient(app) as client:
            yield client
    finally:
        app.state.engine = previous_engine
        app.dependency_overrides.clear()


@pytest.fixture(name="client")
def client_fixture(session: Session, fx: IFXRateProvider) -> Iterator[TestClient]:
    with _wired_client(session, fx) as client:
        yield client


@pytest.fixture(name="buyer_user")
def buyer_user_fixture(session: Session) -> tuple[User, Entity]:
    """A registered-style buyer: entity + USD/EUR accounts + user."""
    entity = make_entity(session, "Buyer Co", "TAX-BUYER", "US")
    make_account(session, entity.id, "USD", Decimal("50000"))
    make_account(session, entity.id, "EUR", Decimal("0"))
    return make_user(session, entity, email="buyer@test.dev"), entity


@pytest.fixture(name="seller_user")
def seller_user_fixture(session: Session) -> tuple[User, Entity]:
    entity = make_entity(session, "Seller Co", "TAX-SELLER", "DE")
    make_account(session, entity.id, "USD", Decimal("0"))
    make_account(session, entity.id, "EUR", Decimal("0"))
    return make_user(session, entity, email="seller@test.dev"), entity


@pytest.fixture(name="auth_buyer_client")
def auth_buyer_client_fixture(
    session: Session, fx: IFXRateProvider, buyer_user: tuple[User, Entity]
) -> Iterator[TestClient]:
    user, _ = buyer_user
    with _wired_client(session, fx) as client:
        client.headers["Authorization"] = f"Bearer {AuthService.issue_token(user)}"
        yield client


@pytest.fixture(name="auth_seller_client")
def auth_seller_client_fixture(
    session: Session, fx: IFXRateProvider, seller_user: tuple[User, Entity]
) -> Iterator[TestClient]:
    user, _ = seller_user
    with _wired_client(session, fx) as client:
        client.headers["Authorization"] = f"Bearer {AuthService.issue_token(user)}"
        yield client


def make_entity(
    session: Session, name: str = "Acme", ubo: str = "TAX-1", country: str = "US"
) -> Entity:
    return EntityRepository(session).create(
        Entity(legal_name=name, ubo_tax_id=ubo, country=country)
    )


def make_user(
    session: Session,
    entity: Entity,
    email: str = "user@test.dev",
    password: str = "test-password-1",
) -> User:
    """Persist an active user bound to an entity (same shape register
    produces, minus the account boot)."""
    from pwdlib import PasswordHash

    return UserRepository(session).create(
        User(
            email=email,
            hashed_password=PasswordHash.recommended().hash(password),
            entity_id=entity.id,
        )
    )


def make_account(
    session: Session,
    entity_id: uuid.UUID,
    currency: str = "USD",
    balance: Decimal = Decimal("0"),
) -> Account:
    repo = AccountRepository(session)
    account = repo.create(
        Account(
            entity_id=entity_id,
            account_type=AccountType.USER_ACCOUNT,
            currency=currency,
        )
    )
    if balance > 0:
        clearing = repo.get_or_create_system_account(AccountType.WISE_CLEARING_ACCOUNT, currency)
        LedgerService(session, repo).record_transfer(
            clearing.id, account.id, balance, currency, "Test funding"
        )
        session.refresh(account)
    return account
