"""Concurrency stress suite — real parallel HTTP traffic against a
file-backed SQLite engine so connection-level locking actually engages.

Invariants under race:
- Exactly one of N duplicate webhook deliveries executes the ledger;
  the rest replay the cached idempotent response.
- Concurrent fund attempts can never overdraw an account; exactly one
  wins and losers fail with typed domain errors.
"""

import os
import tempfile
import uuid
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from decimal import Decimal
from typing import Any

from fastapi.testclient import TestClient
from httpx import Response
from sqlalchemy.engine import Engine
from sqlmodel import Session, SQLModel, select

from app.database import build_engine, get_session
from app.dependencies import get_fx_provider
from app.main import app
from app.middleware.idempotency import drain_pending
from app.models.entities import Account, AccountType, Entity
from app.models.escrow import ContractStatus, EscrowContract, TrackingType
from app.models.idempotency import IdempotencyRecord, WebhookDeliveryLog
from app.models.ledger import LedgerTransaction
from app.repositories.account_repository import AccountRepository, EntityRepository
from app.repositories.escrow_repository import EscrowRepository
from app.services.escrow_service import EscrowService
from app.services.ledger_service import LedgerService

PR_URL = "https://github.com/acme/robotics/pull/99"


class ParityFX:
    """USD->EUR rate of exactly 1 for round-number assertions."""

    def get_rate(self, from_currency: str, to_currency: str) -> Decimal:
        return Decimal("1")


@contextmanager
def file_engine() -> Iterator[Engine]:
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    engine = build_engine(f"sqlite:///{path}")
    SQLModel.metadata.create_all(engine)
    try:
        yield engine
    finally:
        engine.dispose()
        os.unlink(path)


@contextmanager
def wired_app(engine: Engine) -> Iterator[None]:
    """Point dependency injection + middleware storage at a fresh
    file-backed database (fresh session per request)."""

    def override_session() -> Iterator[Session]:
        with Session(engine) as session:
            yield session

    app.dependency_overrides[get_session] = override_session
    app.dependency_overrides[get_fx_provider] = lambda: ParityFX()
    previous = app.state.engine
    app.state.engine = engine
    try:
        yield
    finally:
        app.state.engine = previous
        app.dependency_overrides.clear()


def _seed_funded_contract(
    engine: Engine, deposit: Decimal, tracking_key: str = PR_URL
) -> tuple[uuid.UUID, uuid.UUID, uuid.UUID, uuid.UUID]:
    """Create buyer/seller/accounts and one funded 10000-EUR contract.
    Returns (contract_id, buyer_id, seller_id, buyer_account_id)."""
    with Session(engine) as session:
        entities = EntityRepository(session)
        accounts = AccountRepository(session)
        service = EscrowService(
            session,
            entities,
            accounts,
            EscrowRepository(session),
            LedgerService(session, accounts),
            ParityFX(),
        )
        buyer = entities.create(Entity(legal_name="B", ubo_tax_id="U-B", country="US"))
        seller = entities.create(Entity(legal_name="S", ubo_tax_id="U-S", country="DE"))
        buyer_acc = accounts.create(
            Account(
                entity_id=buyer.id,
                account_type=AccountType.USER_ACCOUNT,
                currency="USD",
            )
        )
        accounts.create(
            Account(
                entity_id=seller.id,
                account_type=AccountType.USER_ACCOUNT,
                currency="EUR",
            )
        )
        clearing = accounts.get_or_create_system_account(AccountType.WISE_CLEARING_ACCOUNT, "USD")
        session.commit()
        LedgerService(session, accounts).record_transfer(
            clearing.id, buyer_acc.id, Decimal("50000"), "USD", "seed"
        )
        contract = service.create_contract(
            buyer.id,
            seller.id,
            "EUR",
            Decimal("10000"),
            "USD",
            TrackingType.GITHUB_PR,
            tracking_key,
        )
        service.fund_contract(contract.id, buyer_acc.id, deposit)
        ids = (contract.id, buyer.id, seller.id, buyer_acc.id)
        return ids


def _post(path: str, payload: dict[str, Any]) -> Response:
    with TestClient(app) as client:
        response: Response = client.post(path, json=payload)
        return response


def test_concurrent_webhook_settlement_race() -> None:
    """10 parallel identical GitHub merge deliveries: exactly 1 executes
    the ledger legs; the other 9 replay the cached response."""
    with file_engine() as engine, wired_app(engine):
        # deposit 10050.25 -> fee 50.25, net 10000.00 -> escrowed 10000.00
        contract_id, _, seller_id, _ = _seed_funded_contract(engine, Decimal("10050.25"))
        payload = {
            "action": "closed",
            "pull_request": {"merged": True, "html_url": PR_URL},
        }
        with ThreadPoolExecutor(max_workers=10) as pool:
            responses = list(pool.map(lambda _: _post("/webhooks/github", payload), range(10)))

        replays = [r for r in responses if r.headers.get("x-idempotent-replay")]
        originals = [r for r in responses if not r.headers.get("x-idempotent-replay")]
        assert len(originals) == 1
        assert len(replays) == 9
        for r in responses:
            assert r.status_code == 200
            assert r.json()["status"] == "settled"
        drain_pending()

        with Session(engine) as session:
            contract = session.get(EscrowContract, contract_id)
            assert contract is not None and contract.status == ContractStatus.SETTLED

            legs = session.exec(
                select(LedgerTransaction).where(LedgerTransaction.contract_id == contract_id)
            ).all()
            release_legs = [tx for tx in legs if "release to seller" in tx.description]
            assert len(release_legs) == 1

            seller_acc = session.exec(
                select(Account).where(
                    Account.entity_id == seller_id,
                    Account.account_type == AccountType.USER_ACCOUNT,
                )
            ).one()
            assert seller_acc.balance == Decimal("9920.00")

            repo = AccountRepository(session)
            fee_eur = repo.get_or_create_system_account(AccountType.WISE_FEE_ACCOUNT, "EUR")
            session.refresh(fee_eur)
            assert fee_eur.balance == Decimal("80.00")

            # Audit trail: 1 execution + 9 duplicates logged.
            logs = session.exec(
                select(WebhookDeliveryLog).where(WebhookDeliveryLog.provider == "github")
            ).all()
            statuses = sorted(log.processed_status for log in logs)
            assert statuses.count("SUCCESS") == 1
            assert statuses.count("DUPLICATE") == 9

            records = session.exec(select(IdempotencyRecord)).all()
            assert len(records) == 1
            assert records[0].status_code == 200


def test_concurrent_balance_depletion_race() -> None:
    """Buyer has $11,000; 5 concurrent fund attempts at $10,100 each on
    two distinct contracts — exactly one may succeed."""
    with file_engine() as engine, wired_app(engine):
        with Session(engine) as session:
            entities = EntityRepository(session)
            accounts = AccountRepository(session)
            buyer = entities.create(Entity(legal_name="B", ubo_tax_id="U-B", country="US"))
            seller = entities.create(Entity(legal_name="S", ubo_tax_id="U-S", country="DE"))
            buyer_acc = accounts.create(
                Account(
                    entity_id=buyer.id,
                    account_type=AccountType.USER_ACCOUNT,
                    currency="USD",
                )
            )
            clearing = accounts.get_or_create_system_account(
                AccountType.WISE_CLEARING_ACCOUNT, "USD"
            )
            session.commit()
            LedgerService(session, accounts).record_transfer(
                clearing.id, buyer_acc.id, Decimal("11000"), "USD", "seed"
            )
            service = EscrowService(
                session,
                entities,
                accounts,
                EscrowRepository(session),
                LedgerService(session, accounts),
                ParityFX(),
            )
            contracts = [
                service.create_contract(
                    buyer.id,
                    seller.id,
                    "EUR",
                    Decimal("10000"),
                    "USD",
                    TrackingType.GITHUB_PR,
                    f"key-{i}",
                )
                for i in range(2)
            ]
            contract_ids = [str(c.id) for c in contracts]
            buyer_account_id = buyer_acc.id

        targets = [contract_ids[i % 2] for i in range(5)]
        payloads = [
            {"account_id": str(buyer_account_id), "deposit_amount": "10100"} for _ in targets
        ]
        with ThreadPoolExecutor(max_workers=5) as pool:
            responses = list(
                pool.map(
                    lambda t: _post(f"/contracts/{t[0]}/fund", t[1]),
                    zip(targets, payloads, strict=False),
                )
            )

        ok = [r for r in responses if r.status_code == 200]
        failed = [r for r in responses if r.status_code != 200]
        assert len(ok) == 1
        assert len(failed) == 4
        for r in failed:
            body = r.json()
            assert body["error_code"] in {
                "ERR_INSUFFICIENT_FUNDS",
                "ERR_INVALID_STATE_TRANSITION",
            }

        with Session(engine) as session:
            buyer_row = session.get(Account, buyer_account_id)
            assert buyer_row is not None
            assert buyer_row.balance == Decimal("900.00")
            assert buyer_row.balance >= 0

            statuses = []
            for cid in contract_ids:
                row = session.get(EscrowContract, uuid.UUID(cid))
                assert row is not None
                statuses.append(row.status)
            assert statuses.count(ContractStatus.FUNDED) == 1
            assert statuses.count(ContractStatus.INITIATED) == 1


def test_concurrent_settle_vs_cancel_race() -> None:
    """Parallel settle-webhook and cancel requests on the same funded
    contract: exactly one terminal transition wins, the ledger shows
    release XOR refund legs — never both."""
    with file_engine() as engine, wired_app(engine):
        contract_id, _, seller_id, _ = _seed_funded_contract(engine, Decimal("10050.25"))
        payload = {
            "action": "closed",
            "pull_request": {"merged": True, "html_url": PR_URL},
        }
        with ThreadPoolExecutor(max_workers=8) as pool:
            futures = [pool.submit(_post, "/webhooks/github", payload) for _ in range(4)] + [
                pool.submit(_post, f"/contracts/{contract_id}/cancel", {}) for _ in range(4)
            ]
            responses = [f.result() for f in futures]
        drain_pending()

        # every response is either a valid outcome or a typed domain error
        for r in responses:
            if r.status_code >= 400:
                assert r.json()["error_code"].startswith("ERR_")

        with Session(engine) as session:
            contract = session.get(EscrowContract, contract_id)
            assert contract is not None
            legs = session.exec(
                select(LedgerTransaction).where(LedgerTransaction.contract_id == contract_id)
            ).all()
            releases = [tx for tx in legs if "release to seller" in tx.description]
            refunds = [tx for tx in legs if "liquidation" in tx.description]
            # exactly one terminal outcome — never both paths applied
            assert bool(releases) != bool(refunds)
            if contract.status == ContractStatus.SETTLED:
                assert len(releases) == 1
                seller_acc = session.exec(
                    select(Account).where(
                        Account.entity_id == seller_id,
                        Account.account_type == AccountType.USER_ACCOUNT,
                    )
                ).one()
                assert seller_acc.balance == Decimal("9920.00")
            else:
                assert contract.status == ContractStatus.CANCELLED
                assert len(refunds) == 1


def test_idempotency_key_header_dedup() -> None:
    """X-Idempotency-Key on a non-webhook POST dedupes re-execution:
    a second fund call with the same key replays the first response
    instead of failing on the state machine."""
    with file_engine() as engine, wired_app(engine):
        with Session(engine) as session:
            entities = EntityRepository(session)
            accounts = AccountRepository(session)
            buyer = entities.create(Entity(legal_name="B", ubo_tax_id="U-B", country="US"))
            seller = entities.create(Entity(legal_name="S", ubo_tax_id="U-S", country="DE"))
            buyer_acc = accounts.create(
                Account(
                    entity_id=buyer.id,
                    account_type=AccountType.USER_ACCOUNT,
                    currency="USD",
                )
            )
            clearing = accounts.get_or_create_system_account(
                AccountType.WISE_CLEARING_ACCOUNT, "USD"
            )
            session.commit()
            LedgerService(session, accounts).record_transfer(
                clearing.id, buyer_acc.id, Decimal("20000"), "USD", "seed"
            )
            service = EscrowService(
                session,
                entities,
                accounts,
                EscrowRepository(session),
                LedgerService(session, accounts),
                ParityFX(),
            )
            contract = service.create_contract(
                buyer.id,
                seller.id,
                "EUR",
                Decimal("5000"),
                "USD",
                TrackingType.GITHUB_PR,
                "key-dedup",
            )
            contract_id = contract.id
            account_id = buyer_acc.id

        def fund() -> Response:
            with TestClient(app) as client:
                response: Response = client.post(
                    f"/contracts/{contract_id}/fund",
                    json={
                        "account_id": str(account_id),
                        "deposit_amount": "5100",
                    },
                    headers={"X-Idempotency-Key": "fund-key-1"},
                )
                return response

        r1 = fund()
        drain_pending()
        r2 = fund()
        assert r1.status_code == 200
        assert r1.json()["status"] == "FUNDED"
        # second identical-key call replays instead of 409ing
        assert r2.status_code == 200
        assert r2.headers.get("x-idempotent-replay") == "1"
        assert r2.json()["status"] == "FUNDED"

        with Session(engine) as session:
            legs = session.exec(
                select(LedgerTransaction).where(LedgerTransaction.contract_id == contract_id)
            ).all()
            fee_legs = [t for t in legs if "conversion fee" in t.description]
            assert len(fee_legs) == 1, "funding ran twice"


def test_webhook_replay_protection_old_timestamp() -> None:
    """Deliveries carrying timestamps older than 300s are rejected."""
    with file_engine() as engine, wired_app(engine):
        import time

        stale = {
            "action": "closed",
            "timestamp": time.time() - 301,
            "pull_request": {"merged": True, "html_url": PR_URL},
        }
        resp = _post("/webhooks/github", stale)
        assert resp.status_code == 400
        assert resp.json()["error_code"] == "ERR_WEBHOOK_VALIDATION"

        fresh = {
            "action": "closed",
            "timestamp": time.time() - 60,
            "pull_request": {"merged": True, "html_url": PR_URL},
        }
        resp2 = _post("/webhooks/github", fresh)
        assert resp2.status_code == 200
        assert resp2.json()["status"] == "ignored"
