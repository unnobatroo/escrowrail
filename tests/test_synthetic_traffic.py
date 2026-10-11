"""Synthetic high-volume traffic: 30 randomized contracts across multiple
entities with interleaved lifecycle ops plus injected edge cases, followed
by global ledger invariant checks.
"""

import random
import uuid
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from typing import NamedTuple

from sqlalchemy.engine import Engine
from sqlmodel import Session, select

from app.middleware.idempotency import drain_pending
from app.models.entities import Account, AccountType, Entity
from app.models.escrow import ContractStatus, EscrowContract, TrackingType
from app.models.ledger import LedgerTransaction
from app.repositories.account_repository import AccountRepository, EntityRepository
from app.repositories.escrow_repository import EscrowRepository
from app.services.escrow_service import EscrowService
from app.services.ledger_service import LedgerService
from tests.test_concurrency_and_load import ParityFX, _post, file_engine, wired_app


class ContractSpec(NamedTuple):
    contract_id: uuid.UUID
    buyer_account_id: uuid.UUID
    tracking_key: str


def _seed(
    engine: Engine, n_entities: int
) -> tuple[dict[uuid.UUID, uuid.UUID], dict[uuid.UUID, uuid.UUID]]:
    """Create buyers (funded USD accounts) and sellers (EUR accounts).
    Returns ({entity_id: account_id}, {entity_id: account_id})."""
    with Session(engine) as session:
        entities = EntityRepository(session)
        accounts = AccountRepository(session)
        ledger = LedgerService(session, accounts)
        clearing = accounts.get_or_create_system_account(AccountType.ESCROWRAIL_CLEARING_ACCOUNT, "USD")
        session.commit()
        buyers: dict[uuid.UUID, uuid.UUID] = {}
        sellers: dict[uuid.UUID, uuid.UUID] = {}
        for i in range(n_entities):
            buyer = entities.create(Entity(legal_name=f"B{i}", ubo_tax_id=f"UB{i}", country="US"))
            seller = entities.create(Entity(legal_name=f"S{i}", ubo_tax_id=f"US{i}", country="DE"))
            session.commit()
            ba = accounts.create(
                Account(
                    entity_id=buyer.id,
                    account_type=AccountType.USER_ACCOUNT,
                    currency="USD",
                )
            )
            sa = accounts.create(
                Account(
                    entity_id=seller.id,
                    account_type=AccountType.USER_ACCOUNT,
                    currency="EUR",
                )
            )
            session.commit()
            ledger.record_transfer(clearing.id, ba.id, Decimal("50000"), "USD", "seed")
            buyers[buyer.id] = ba.id
            sellers[seller.id] = sa.id
        return buyers, sellers


def test_synthetic_high_volume_traffic() -> None:
    rng = random.Random(42)  # deterministic run
    with file_engine() as engine, wired_app(engine):
        buyers, sellers = _seed(engine, 6)
        buyer_ids = list(buyers)
        seller_ids = list(sellers)
        specs: list[ContractSpec] = []
        ubo_rejections = 0

        # Contract creation via the service layer (routing covered
        # elsewhere); all money movement goes through HTTP for realism.
        with Session(engine) as session:
            service = EscrowService(
                session,
                EntityRepository(session),
                AccountRepository(session),
                EscrowRepository(session),
                LedgerService(session, AccountRepository(session)),
                ParityFX(),
            )
            for i in range(30):
                buyer_id = buyer_ids[i % len(buyer_ids)]
                seller_id = seller_ids[(i * 2 + 1) % len(seller_ids)]
                if i % 10 == 9:
                    # Injected edge: contract between entities sharing a
                    # UBO tax id must be rejected.
                    twin = EntityRepository(session).create(
                        Entity(
                            legal_name=f"TWIN{i}",
                            ubo_tax_id=f"UB{buyer_ids.index(buyer_id)}",
                            country="US",
                        )
                    )
                    session.commit()
                    try:
                        service.create_contract(
                            buyer_id,
                            twin.id,
                            "EUR",
                            Decimal("100"),
                            "USD",
                            TrackingType.GITHUB_PR,
                            f"ubo-{i}",
                        )
                    except Exception:
                        ubo_rejections += 1
                    continue
                contract = service.create_contract(
                    buyer_id,
                    seller_id,
                    "EUR",
                    Decimal(rng.randint(100, 5000)).quantize(Decimal("0.01")),
                    "USD",
                    TrackingType.GITHUB_PR,
                    f"https://github.com/acme/repo/pull/{1000 + i}",
                )
                specs.append(ContractSpec(contract.id, buyers[buyer_id], contract.tracking_key))
            assert ubo_rejections == 3
            assert len(specs) == 27

        # Interleaved fund -> (settle via duplicate webhooks | cancel).
        def fund(spec: ContractSpec) -> int:
            return _post(
                f"/contracts/{spec.contract_id}/fund",
                {
                    "account_id": str(spec.buyer_account_id),
                    "deposit_amount": "20000",
                },
            ).status_code

        with ThreadPoolExecutor(max_workers=8) as pool:
            statuses = list(pool.map(fund, specs))
        funded = [s for s, st in zip(specs, statuses, strict=False) if st == 200]
        assert funded, "no contract funded — check test wiring"

        settle_specs = [s for s in funded if rng.random() < 0.7]
        cancel_specs = [s for s in funded if s not in settle_specs]

        def settle(spec: ContractSpec) -> int:
            payload = {
                "action": "closed",
                "pull_request": {
                    "merged": True,
                    "html_url": spec.tracking_key,
                },
            }
            # Two identical deliveries: one must execute, one must replay.
            r1 = _post("/webhooks/github", payload)
            r2 = _post("/webhooks/github", payload)
            assert r2.headers.get("x-idempotent-replay") or (
                r1.headers.get("x-idempotent-replay")
            ), "duplicate webhook was not deduplicated"
            return max(r1.status_code, r2.status_code)

        def cancel(spec: ContractSpec) -> int:
            return _post(f"/contracts/{spec.contract_id}/cancel", {}).status_code

        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(settle, settle_specs))
            list(pool.map(cancel, cancel_specs))
        drain_pending()

        # ---- post-run invariants ----
        with Session(engine) as session:
            legs = session.exec(select(LedgerTransaction)).all()
            accounts = session.exec(select(Account)).all()

            # 1. Conservation: recomputing every balance from the legs and
            #    summing per currency nets to zero.
            for acc in accounts:
                expected = sum(tx.amount for tx in legs if tx.credit_account_id == acc.id) - sum(
                    tx.amount for tx in legs if tx.debit_account_id == acc.id
                )
                assert acc.balance == expected, (
                    f"{acc.currency} account {acc.id}: stored {acc.balance} != ledger {expected}"
                )
            for currency in {a.currency for a in accounts}:
                total = sum(a.balance for a in accounts if a.currency == currency)
                assert total == Decimal("0.00"), f"{currency} net {total}"

            # 2. Every contract is in a reachable state; no duplicate
            #    settle legs (one release leg per settled contract).
            by_status: dict[ContractStatus, list[EscrowContract]] = {}
            for spec in specs:
                c = session.get(EscrowContract, spec.contract_id)
                assert c is not None
                by_status.setdefault(c.status, []).append(c)
            settled = by_status.get(ContractStatus.SETTLED, [])
            cancelled = by_status.get(ContractStatus.CANCELLED, [])
            assert settled, "no contract settled"
            assert cancelled, "no contract cancelled"
            for c in settled:
                releases = [
                    tx
                    for tx in legs
                    if tx.contract_id == c.id and "release to seller" in tx.description
                ]
                assert len(releases) == 1, f"{c.id}: {len(releases)} release legs"

            # 3. Clearing netting: EUR clearing holds -escrowed for every
            #    funded/settled contract; cancelled contracts net to zero.
            clearing_eur = AccountRepository(session).get_or_create_system_account(
                AccountType.ESCROWRAIL_CLEARING_ACCOUNT, "EUR"
            )
            session.refresh(clearing_eur)

            def clearing_net(contract_id: uuid.UUID) -> Decimal:
                net = Decimal(0)
                for tx in legs:
                    if tx.contract_id != contract_id or tx.currency != "EUR":
                        continue
                    if tx.credit_account_id == clearing_eur.id:
                        net += tx.amount
                    if tx.debit_account_id == clearing_eur.id:
                        net -= tx.amount
                return net

            for c in settled:
                assert c.escrowed_amount is not None
                assert clearing_net(c.id) == -c.escrowed_amount
            for c in cancelled:
                assert clearing_net(c.id) == Decimal("0.00")
            for c in by_status.get(ContractStatus.FUNDED, []):
                assert c.escrowed_amount is not None
                assert clearing_net(c.id) == -c.escrowed_amount
