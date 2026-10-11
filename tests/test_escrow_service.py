"""EscrowService: UBO validation, spot conversion, fee deductions,
cancellation math, state machine enforcement."""

import uuid
from decimal import Decimal

import pytest
from sqlmodel import Session

from app.exceptions import (
    CircularOwnershipError,
    InsufficientFundsError,
    InvalidStateTransitionError,
)
from app.models.entities import AccountType, Entity
from app.models.escrow import ContractStatus, EscrowContract, TrackingType
from app.repositories.account_repository import AccountRepository
from app.services.escrow_service import EscrowService
from tests.conftest import StubFXProvider, make_account, make_entity


def _buyer_seller(session: Session) -> tuple[Entity, Entity]:
    buyer = make_entity(session, "Buyer Co", "TAX-B1", "US")
    seller = make_entity(session, "Seller Co", "TAX-S1", "DE")
    return buyer, seller


def _make_contract(
    service: EscrowService, buyer_id: uuid.UUID, seller_id: uuid.UUID, key: str = "k1"
) -> EscrowContract:
    return service.create_contract(
        buyer_id=buyer_id,
        seller_id=seller_id,
        target_currency="EUR",
        target_amount=Decimal("10000"),
        source_currency="USD",
        tracking_type=TrackingType.GITHUB_PR,
        tracking_key=key,
    )


def test_ubo_collision_rejected(session: Session, escrow_service: EscrowService) -> None:
    buyer = make_entity(session, "Acme US", "TAX-SHARED", "US")
    sub = make_entity(session, "Acme DE", "TAX-SHARED", "DE")
    with pytest.raises(CircularOwnershipError):
        escrow_service.create_contract(
            buyer_id=buyer.id,
            seller_id=sub.id,
            target_currency="EUR",
            target_amount=Decimal("1000"),
            source_currency="USD",
            tracking_type=TrackingType.GITHUB_PR,
            tracking_key="pr-1",
        )


def test_funding_converts_at_spot_and_collects_fee(
    session: Session, escrow_service: EscrowService
) -> None:
    buyer, seller = _buyer_seller(session)
    buyer_usd = make_account(session, buyer.id, "USD", Decimal("20000"))
    make_account(session, seller.id, "EUR", Decimal("0"))
    contract = _make_contract(escrow_service, buyer.id, seller.id)

    funded = escrow_service.fund_contract(contract.id, buyer.id, buyer_usd.id, Decimal("11000"))

    assert funded.status == ContractStatus.FUNDED
    assert funded.deposit_amount == Decimal("11000")
    assert funded.locked_spot_rate == (Decimal(1) / Decimal("1.08")).quantize(Decimal("0.00000001"))

    session.refresh(buyer_usd)
    assert buyer_usd.balance == Decimal("9000")

    repo = AccountRepository(session)
    fee_usd = repo.get_or_create_system_account(AccountType.ESCROWRAIL_FEE_ACCOUNT, "USD")
    vault_eur = repo.get_or_create_system_account(AccountType.SAFEGUARDED_ESCROW_VAULT, "EUR")
    session.refresh(fee_usd)
    session.refresh(vault_eur)
    assert fee_usd.balance == Decimal("55.00")  # 0.5% of 11000
    # (11000 - 55) / 1.08 = 10134.26 EUR lands directly in the vault
    assert vault_eur.balance == Decimal("10134.26")


def test_settlement_splits_99_2_and_0_8(session: Session, escrow_service: EscrowService) -> None:
    buyer, seller = _buyer_seller(session)
    buyer_usd = make_account(session, buyer.id, "USD", Decimal("20000"))
    seller_eur = make_account(session, seller.id, "EUR", Decimal("0"))
    contract = _make_contract(escrow_service, buyer.id, seller.id)
    escrow_service.fund_contract(contract.id, buyer.id, buyer_usd.id, Decimal("11000"))

    settled = escrow_service.settle_milestone(contract.id)
    assert settled.status == ContractStatus.SETTLED

    session.refresh(seller_eur)
    repo = AccountRepository(session)
    fee_eur = repo.get_or_create_system_account(AccountType.ESCROWRAIL_FEE_ACCOUNT, "EUR")
    vault_eur = repo.get_or_create_system_account(AccountType.SAFEGUARDED_ESCROW_VAULT, "EUR")
    session.refresh(fee_eur)
    session.refresh(vault_eur)
    # escrowed 10134.26 -> fee 81.07 (0.8%), seller 10053.19 (99.2%)
    assert fee_eur.balance == Decimal("81.07")
    assert seller_eur.balance == Decimal("10053.19")
    assert vault_eur.balance == Decimal("0")


def test_deposit_too_small_rejected(session: Session, escrow_service: EscrowService) -> None:
    buyer, seller = _buyer_seller(session)
    buyer_usd = make_account(session, buyer.id, "USD", Decimal("20000"))
    contract = _make_contract(escrow_service, buyer.id, seller.id)
    with pytest.raises(InsufficientFundsError):
        escrow_service.fund_contract(contract.id, buyer.id, buyer_usd.id, Decimal("5000"))


def test_insufficient_buyer_balance_rejected(
    session: Session, escrow_service: EscrowService
) -> None:
    buyer, seller = _buyer_seller(session)
    buyer_usd = make_account(session, buyer.id, "USD", Decimal("100"))
    contract = _make_contract(escrow_service, buyer.id, seller.id)
    with pytest.raises(InsufficientFundsError):
        escrow_service.fund_contract(contract.id, buyer.id, buyer_usd.id, Decimal("11000"))


def test_settle_requires_funded_status(session: Session, escrow_service: EscrowService) -> None:
    buyer, seller = _buyer_seller(session)
    contract = _make_contract(escrow_service, buyer.id, seller.id)
    with pytest.raises(InvalidStateTransitionError):
        escrow_service.settle_milestone(contract.id)


def test_double_funding_rejected(session: Session, escrow_service: EscrowService) -> None:
    buyer, seller = _buyer_seller(session)
    buyer_usd = make_account(session, buyer.id, "USD", Decimal("50000"))
    contract = _make_contract(escrow_service, buyer.id, seller.id)
    escrow_service.fund_contract(contract.id, buyer.id, buyer_usd.id, Decimal("11000"))
    with pytest.raises(InvalidStateTransitionError):
        escrow_service.fund_contract(contract.id, buyer.id, buyer_usd.id, Decimal("11000"))


def test_cancellation_refunds_at_latest_spot(
    session: Session, escrow_service: EscrowService, fx: StubFXProvider
) -> None:
    buyer, seller = _buyer_seller(session)
    buyer_usd = make_account(session, buyer.id, "USD", Decimal("20000"))
    contract = _make_contract(escrow_service, buyer.id, seller.id)
    escrow_service.fund_contract(contract.id, buyer.id, buyer_usd.id, Decimal("11000"))

    # EUR weakens: 1 EUR = 0.90 USD at cancellation -> buyer absorbs the loss.
    fx.usd_values["EUR"] = Decimal("0.90")
    cancelled = escrow_service.cancel_contract(contract.id, buyer.id)

    assert cancelled.status == ContractStatus.CANCELLED
    session.refresh(buyer_usd)
    # Started 20000, paid 11000, escrowed 10134.26 EUR -> refund * 0.90
    assert buyer_usd.balance == Decimal("18120.83")

    # Escrowrail retains the original 0.5% conversion fee.
    fee_usd = AccountRepository(session).get_or_create_system_account(
        AccountType.ESCROWRAIL_FEE_ACCOUNT, "USD"
    )
    session.refresh(fee_usd)
    assert fee_usd.balance == Decimal("55.00")


def test_cancel_before_funding_is_noop(session: Session, escrow_service: EscrowService) -> None:
    buyer, seller = _buyer_seller(session)
    contract = _make_contract(escrow_service, buyer.id, seller.id)
    cancelled = escrow_service.cancel_contract(contract.id, buyer.id)
    assert cancelled.status == ContractStatus.CANCELLED


def test_cancel_after_settle_rejected(session: Session, escrow_service: EscrowService) -> None:
    buyer, seller = _buyer_seller(session)
    buyer_usd = make_account(session, buyer.id, "USD", Decimal("20000"))
    make_account(session, seller.id, "EUR", Decimal("0"))
    contract = _make_contract(escrow_service, buyer.id, seller.id)
    escrow_service.fund_contract(contract.id, buyer.id, buyer_usd.id, Decimal("11000"))
    escrow_service.settle_milestone(contract.id)
    with pytest.raises(InvalidStateTransitionError):
        escrow_service.cancel_contract(contract.id, buyer.id)
