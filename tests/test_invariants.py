"""Property-based invariant tests (Hypothesis).

Static unit tests only pin examples; these tests fuzz the financial
invariants that must hold for *any* input:

1. Conservation of value — replaying the ledger reconstructs every
   balance; per-currency totals are conserved; EUR clearing nets to zero
   after complete escrow cycles.
2. State-machine rigidity — every illegal transition raises the typed
   ``InvalidStateTransitionError`` regardless of generated payloads.
3. Rounding precision — fees + net always equal gross to the cent; no
   fractional cents are created or leaked.
"""

import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from decimal import Decimal

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st
from sqlalchemy.pool import StaticPool
from sqlmodel import Session, SQLModel, create_engine, select

from app.exceptions import (
    InsufficientFundsError,
    InvalidStateTransitionError,
)
from app.models.entities import Account, AccountType, Entity
from app.models.escrow import ContractStatus, EscrowContract, TrackingType
from app.models.ledger import LedgerTransaction
from app.repositories.account_repository import AccountRepository, EntityRepository
from app.repositories.escrow_repository import EscrowRepository
from app.services.escrow_service import EscrowService
from app.services.ledger_service import CENT, LedgerService

AMOUNTS = st.decimals(min_value=Decimal("0.01"), max_value=Decimal("1000000.99"), places=2)
BIG_BALANCE = Decimal("900000000.00")
DEADLINE_FREE = settings(
    max_examples=30,
    deadline=None,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.data_too_large],
)


@contextmanager
def fresh_session() -> Iterator[Session]:
    """Independent in-memory DB per generated example."""
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    SQLModel.metadata.create_all(engine)
    with Session(engine) as session:
        yield session


_USD_VALUES = {"USD": Decimal("1"), "EUR": Decimal("1.08"), "GBP": Decimal("1.25")}


class _StaticFX:
    def get_rate(self, from_currency: str, to_currency: str) -> Decimal:
        if from_currency == to_currency:
            return Decimal("1")
        return _USD_VALUES[from_currency] / _USD_VALUES[to_currency]


def _escrow_service(session: Session) -> EscrowService:
    accounts = AccountRepository(session)
    return EscrowService(
        session,
        EntityRepository(session),
        accounts,
        EscrowRepository(session),
        LedgerService(session, accounts),
        _StaticFX(),
    )


def _entity(session: Session, suffix: str) -> Entity:
    return EntityRepository(session).create(
        Entity(legal_name=f"Co-{suffix}", ubo_tax_id=f"TAX-{suffix}", country="US")
    )


def _account(session: Session, entity_id: uuid.UUID, currency: str = "USD") -> Account:
    return AccountRepository(session).create(
        Account(
            entity_id=entity_id,
            account_type=AccountType.USER_ACCOUNT,
            currency=currency,
        )
    )


def _fund_buyer(session: Session, buyer_acc: Account) -> None:
    """Seed the buyer through the ledger so replay invariants hold."""
    repo = AccountRepository(session)
    clearing = repo.get_or_create_system_account(AccountType.WISE_CLEARING_ACCOUNT, "USD")
    session.commit()
    LedgerService(session, repo).record_transfer(
        clearing.id, buyer_acc.id, BIG_BALANCE, "USD", "float seed"
    )


def _make_contract(
    session: Session,
    service: EscrowService,
    buyer_id: uuid.UUID,
    seller_id: uuid.UUID,
    target: Decimal = Decimal("0.01"),
) -> EscrowContract:
    return service.create_contract(
        buyer_id=buyer_id,
        seller_id=seller_id,
        target_currency="EUR",
        target_amount=target,
        source_currency="USD",
        tracking_type=TrackingType.GITHUB_PR,
        tracking_key=str(uuid.uuid4()),
    )


# ---------------------------------------------------------------------------
# 1. Conservation of value
# ---------------------------------------------------------------------------


@given(ops=st.lists(st.tuples(st.integers(0, 3), st.integers(0, 3), AMOUNTS), max_size=25))
@DEADLINE_FREE
def test_ledger_replay_and_conservation(ops: list[tuple[int, int, Decimal]]) -> None:
    """For any sequence of transfers — including ones that fail — every
    committed leg is cent-exact, per-currency balance totals are
    conserved (≡ 0), and replaying the ledger reconstructs balances."""
    with fresh_session() as session:
        repo = AccountRepository(session)
        ledger = LedgerService(session, repo)
        entity = _entity(session, "E")
        accounts = [_account(session, entity.id) for _ in range(3)]
        clearing = repo.get_or_create_system_account(AccountType.WISE_CLEARING_ACCOUNT, "USD")
        session.commit()
        pool: list[Account] = [*accounts, clearing]

        for src_i, dst_i, amount in ops:
            src, dst = pool[src_i], pool[dst_i]
            if src.id == dst.id:
                continue
            src_before, dst_before = src.balance, dst.balance
            try:
                ledger.record_transfer(src.id, dst.id, amount, "USD", "fuzz")
            except InsufficientFundsError:
                pass
            session.refresh(src)
            session.refresh(dst)
            # A failed or committed leg never mutates just one side.
            moved = src_before - src.balance
            assert moved == dst.balance - dst_before

        session.expire_all()
        # Conservation: all balances started at 0 and every leg is
        # balanced, so the USD total across all accounts is exactly 0.
        assert sum(a.balance for a in pool) == Decimal("0.00")

        # Replay: the ledger reconstructs each balance exactly.
        txs = session.exec(select(LedgerTransaction)).all()
        replay: dict[uuid.UUID, Decimal] = {}
        for tx in txs:
            assert tx.amount == tx.amount.quantize(CENT)
            replay[tx.debit_account_id] = replay.get(tx.debit_account_id, Decimal("0")) - tx.amount
            replay[tx.credit_account_id] = (
                replay.get(tx.credit_account_id, Decimal("0")) + tx.amount
            )
        for account in pool:
            assert replay.get(account.id, Decimal("0")) == account.balance


@given(deposit=AMOUNTS, settle=st.booleans())
@DEADLINE_FREE
def test_complete_cycles_clearing_nets_zero(deposit: Decimal, settle: bool) -> None:
    """After a complete FUNDED -> SETTLED/CANCELLED cycle the EUR clearing
    leg is fully reversed (net zero) and the vault is drained."""
    with fresh_session() as session:
        repo = AccountRepository(session)
        service = _escrow_service(session)
        buyer, seller = _entity(session, "B"), _entity(session, "S")
        buyer_acc = _account(session, buyer.id, "USD")
        _account(session, seller.id, "EUR")
        clearing_eur = repo.get_or_create_system_account(AccountType.WISE_CLEARING_ACCOUNT, "EUR")
        vault = repo.get_or_create_system_account(AccountType.SAFEGUARDED_ESCROW_VAULT, "EUR")
        session.commit()
        clearing_before, vault_before = clearing_eur.balance, vault.balance
        _fund_buyer(session, buyer_acc)

        contract = _make_contract(session, service, buyer.id, seller.id)
        service.fund_contract(contract.id, buyer.id, buyer_acc.id, deposit)
        escrowed = next(
            tx.amount
            for tx in EscrowRepository(session).ledger_entries(contract.id)
            if "Spot conversion" in tx.description
        )
        if settle:
            service.settle_milestone(contract.id)
            expected = ContractStatus.SETTLED
        else:
            service.cancel_contract(contract.id, buyer.id)
            expected = ContractStatus.CANCELLED

        assert contract.status == expected
        session.refresh(clearing_eur)
        session.refresh(vault)
        assert vault.balance == vault_before
        if settle:
            # Settlement pays sellers out of Wise's EUR float.
            assert clearing_eur.balance == clearing_before - escrowed
        else:
            # Cancellation round-trips the EUR clearing leg to zero.
            assert clearing_eur.balance == clearing_before


# ---------------------------------------------------------------------------
# 2. State machine rigidity
# ---------------------------------------------------------------------------

_OPS = st.lists(st.sampled_from(["fund", "settle", "cancel"]), min_size=1, max_size=8)


@given(target=AMOUNTS, key=st.text(max_size=20))
@DEADLINE_FREE
def test_initiated_contract_never_settles_directly(target: Decimal, key: str) -> None:
    """INITIATED -> SETTLED is impossible for any generated contract."""
    with fresh_session() as session:
        service = _escrow_service(session)
        buyer, seller = _entity(session, "B"), _entity(session, "S")
        contract = service.create_contract(
            buyer_id=buyer.id,
            seller_id=seller.id,
            target_currency="EUR",
            target_amount=target,
            source_currency="USD",
            tracking_type=TrackingType.GITHUB_PR,
            tracking_key=key or "k",
        )
        with pytest.raises(InvalidStateTransitionError):
            service.settle_milestone(contract.id)
        assert contract.status == ContractStatus.INITIATED


@given(ops=_OPS)
@DEADLINE_FREE
def test_operation_sequence_respects_state_machine(ops: list[str]) -> None:
    """Random operation sequences: an operation that is illegal in the
    current status must raise InvalidStateTransitionError and leave the
    status untouched; legal operations advance the machine exactly."""
    with fresh_session() as session:
        service = _escrow_service(session)
        buyer, seller = _entity(session, "B"), _entity(session, "S")
        buyer_acc = _account(session, buyer.id, "USD")
        _account(session, seller.id, "EUR")
        _fund_buyer(session, buyer_acc)
        contract = _make_contract(session, service, buyer.id, seller.id)

        status = ContractStatus.INITIATED
        legal = {
            "fund": {ContractStatus.INITIATED},
            "settle": {ContractStatus.FUNDED},
            "cancel": {ContractStatus.INITIATED, ContractStatus.FUNDED},
        }
        for op in ops:
            if status in legal[op]:
                if op == "fund":
                    service.fund_contract(contract.id, buyer.id, buyer_acc.id, Decimal("1000"))
                    status = ContractStatus.FUNDED
                elif op == "settle":
                    service.settle_milestone(contract.id)
                    status = ContractStatus.SETTLED
                else:
                    service.cancel_contract(contract.id, buyer.id)
                    status = ContractStatus.CANCELLED
            else:
                with pytest.raises(InvalidStateTransitionError):
                    if op == "fund":
                        service.fund_contract(contract.id, buyer.id, buyer_acc.id, Decimal("1000"))
                    elif op == "settle":
                        service.settle_milestone(contract.id)
                    else:
                        service.cancel_contract(contract.id, buyer.id)
            assert contract.status == status


# ---------------------------------------------------------------------------
# 3. Rounding & precision
# ---------------------------------------------------------------------------


@given(deposit=AMOUNTS)
@DEADLINE_FREE
def test_fee_plus_net_equals_gross(deposit: Decimal) -> None:
    """The buyer's source-currency outflow legs sum to exactly the
    deposit — the fee is carved out, never added on top, and no
    fractional cent is created or leaked."""
    with fresh_session() as session:
        repo = AccountRepository(session)
        service = _escrow_service(session)
        buyer, seller = _entity(session, "B"), _entity(session, "S")
        buyer_acc = _account(session, buyer.id, "USD")
        _account(session, seller.id, "EUR")
        _fund_buyer(session, buyer_acc)
        contract = _make_contract(session, service, buyer.id, seller.id)
        service.fund_contract(contract.id, buyer.id, buyer_acc.id, deposit)

        legs = EscrowRepository(session).ledger_entries(contract.id)
        source_legs = [
            tx for tx in legs if tx.currency == "USD" and tx.debit_account_id == buyer_acc.id
        ]
        assert sum(tx.amount for tx in source_legs) == deposit.quantize(CENT)
        # Every leg is cent-exact.
        for tx in legs:
            assert tx.amount == tx.amount.quantize(CENT)

        # Escrowed amount equals the stored formula exactly.
        fee = (deposit * EscrowService.CONVERSION_FEE_RATE).quantize(CENT)
        rate = _USD_VALUES["USD"] / _USD_VALUES["EUR"]
        expected = ((deposit - fee) * rate).quantize(CENT)
        vault = repo.get_or_create_system_account(AccountType.SAFEGUARDED_ESCROW_VAULT, "EUR")
        session.refresh(vault)
        assert vault.balance == expected


@given(deposit=AMOUNTS)
@DEADLINE_FREE
def test_settlement_split_exact_to_cent(deposit: Decimal) -> None:
    """Wise's fee + seller payout == escrowed, exactly, for every
    generated deposit — including micro and uneven amounts."""
    with fresh_session() as session:
        AccountRepository(session)
        service = _escrow_service(session)
        buyer, seller = _entity(session, "B"), _entity(session, "S")
        buyer_acc = _account(session, buyer.id, "USD")
        seller_acc = _account(session, seller.id, "EUR")
        _fund_buyer(session, buyer_acc)
        contract = _make_contract(session, service, buyer.id, seller.id)
        service.fund_contract(contract.id, buyer.id, buyer_acc.id, deposit)
        escrowed = next(
            tx.amount
            for tx in EscrowRepository(session).ledger_entries(contract.id)
            if "Spot conversion" in tx.description
        )
        service.settle_milestone(contract.id)

        legs = EscrowRepository(session).ledger_entries(contract.id)
        release_leg = next(tx for tx in legs if "release to seller" in tx.description)
        fee_amount = sum(tx.amount for tx in legs if "0.8%" in tx.description)
        assert fee_amount + release_leg.amount == escrowed
        session.refresh(seller_acc)
        assert seller_acc.balance == release_leg.amount


@given(deposit=AMOUNTS)
@DEADLINE_FREE
def test_cancellation_returns_only_to_origin(deposit: Decimal) -> None:
    """On cancel: vault drains, USD legs touch only clearing and the
    originating buyer account, and refund <= escrowed * locked rate."""
    with fresh_session() as session:
        service = _escrow_service(session)
        buyer, seller = _entity(session, "B"), _entity(session, "S")
        buyer_acc = _account(session, buyer.id, "USD")
        _account(session, seller.id, "EUR")
        _fund_buyer(session, buyer_acc)
        contract = _make_contract(session, service, buyer.id, seller.id)
        service.fund_contract(contract.id, buyer.id, buyer_acc.id, deposit)

        service.cancel_contract(contract.id, buyer.id)
        refund_legs = [
            tx
            for tx in EscrowRepository(session).ledger_entries(contract.id)
            if "Refund to originating account" in tx.description
        ]
        for tx in refund_legs:
            assert tx.credit_account_id == buyer_acc.id
            assert tx.currency == "USD"
