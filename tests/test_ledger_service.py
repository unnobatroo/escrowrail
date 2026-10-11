"""LedgerService: atomicity, balance invariants, currency mismatch,
zero-float enforcement."""

import uuid
from decimal import Decimal

import pytest
from sqlmodel import Session, select

from app.exceptions import (
    CurrencyMismatchError,
    InsufficientFundsError,
    InvalidAmountError,
    NotFoundError,
)
from app.models.entities import AccountType
from app.models.ledger import LedgerTransaction
from app.repositories.account_repository import AccountRepository
from app.services.ledger_service import LedgerService
from tests.conftest import make_account, make_entity


def test_balanced_transfer_moves_funds(session: Session) -> None:
    buyer = make_entity(session)
    src = make_account(session, buyer.id, "USD", Decimal("1000"))
    dst = make_account(session, buyer.id, "USD", Decimal("0"))
    ledger = LedgerService(session, AccountRepository(session))

    tx = ledger.record_transfer(src.id, dst.id, Decimal("250.50"), "USD", "test transfer")

    session.refresh(src)
    session.refresh(dst)
    assert src.balance == Decimal("749.50")
    assert dst.balance == Decimal("250.50")
    assert tx.amount == Decimal("250.50")
    assert tx.debit_account_id == src.id
    assert tx.credit_account_id == dst.id


def test_balance_is_decimal_not_float(session: Session) -> None:
    entity = make_entity(session)
    account = make_account(session, entity.id, "USD", Decimal("10.10"))
    session.refresh(account)
    assert isinstance(account.balance, Decimal)


def test_float_amount_rejected(session: Session) -> None:
    entity = make_entity(session)
    src = make_account(session, entity.id, "USD", Decimal("100"))
    dst = make_account(session, entity.id, "USD", Decimal("0"))
    ledger = LedgerService(session, AccountRepository(session))
    with pytest.raises(InvalidAmountError):
        ledger.record_transfer(
            src.id,
            dst.id,
            10.0,  # type: ignore[arg-type]
            "USD",
            "float amount",
        )


def test_non_positive_amount_rejected(session: Session) -> None:
    entity = make_entity(session)
    src = make_account(session, entity.id, "USD", Decimal("100"))
    dst = make_account(session, entity.id, "USD", Decimal("0"))
    ledger = LedgerService(session, AccountRepository(session))
    with pytest.raises(InvalidAmountError):
        ledger.record_transfer(src.id, dst.id, Decimal("0"), "USD", "zero")
    with pytest.raises(InvalidAmountError):
        ledger.record_transfer(src.id, dst.id, Decimal("-5"), "USD", "negative")


def test_currency_mismatch_rejected_and_rolled_back(session: Session) -> None:
    entity = make_entity(session)
    usd = make_account(session, entity.id, "USD", Decimal("100"))
    eur = make_account(session, entity.id, "EUR", Decimal("50"))
    ledger = LedgerService(session, AccountRepository(session))
    tx_count_before = len(session.exec(select(LedgerTransaction)).all())

    with pytest.raises(CurrencyMismatchError):
        ledger.record_transfer(usd.id, eur.id, Decimal("10"), "USD", "mismatch")

    session.refresh(usd)
    session.refresh(eur)
    assert usd.balance == Decimal("100")
    assert eur.balance == Decimal("50")
    assert len(session.exec(select(LedgerTransaction)).all()) == tx_count_before


def test_leg_currency_must_match_accounts(session: Session) -> None:
    entity = make_entity(session)
    usd = make_account(session, entity.id, "USD", Decimal("100"))
    usd2 = make_account(session, entity.id, "USD", Decimal("0"))
    ledger = LedgerService(session, AccountRepository(session))
    with pytest.raises(CurrencyMismatchError):
        ledger.record_transfer(usd.id, usd2.id, Decimal("10"), "EUR", "wrong leg")


def test_insufficient_funds_on_user_account(session: Session) -> None:
    entity = make_entity(session)
    src = make_account(session, entity.id, "USD", Decimal("5"))
    dst = make_account(session, entity.id, "USD", Decimal("0"))
    ledger = LedgerService(session, AccountRepository(session))
    with pytest.raises(InsufficientFundsError):
        ledger.record_transfer(src.id, dst.id, Decimal("10"), "USD", "overdraft")
    session.refresh(src)
    assert src.balance == Decimal("5")


def test_missing_account_raises_not_found(session: Session) -> None:
    entity = make_entity(session)
    src = make_account(session, entity.id, "USD", Decimal("100"))
    ledger = LedgerService(session, AccountRepository(session))
    with pytest.raises(NotFoundError):
        ledger.record_transfer(src.id, uuid.uuid4(), Decimal("10"), "USD", "ghost credit")
    session.refresh(src)
    assert src.balance == Decimal("100")


def test_clearing_account_may_run_negative(session: Session) -> None:
    entity = make_entity(session)
    dst = make_account(session, entity.id, "USD", Decimal("0"))
    repo = AccountRepository(session)
    clearing = repo.get_or_create_system_account(AccountType.WISE_CLEARING_ACCOUNT, "USD")
    session.commit()
    ledger = LedgerService(session, repo)
    ledger.record_transfer(clearing.id, dst.id, Decimal("500"), "USD", "external float")
    session.refresh(clearing)
    assert clearing.balance == Decimal("-500")
