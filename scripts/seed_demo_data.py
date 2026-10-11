"""Seed demo data for a live Escrowrail demonstration.

Run:  python scripts/seed_demo_data.py
Creates demo entities/accounts in the configured database (default
sqlite:///./escrowrail_escrow.db). Idempotent: skips entities that already exist.
"""

import sys
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from sqlmodel import Session, select

from app.database import engine, init_db
from app.models.entities import Account, AccountType, Entity
from app.repositories.account_repository import AccountRepository
from app.services.ledger_service import LedgerService

DEMO_ENTITIES = [
    # name, ubo_tax_id, country, currency, opening balance
    ("Acme Hardware US", "TAX-US-1001", "US", "USD", Decimal("50000.00")),
    ("Berlin Robotics GmbH", "TAX-DE-2002", "DE", "EUR", Decimal("0.00")),
    # Shares Acme's UBO -> demonstrates circular-ownership rejection.
    ("Acme Subsidiary DE", "TAX-US-1001", "DE", "EUR", Decimal("0.00")),
]

SYSTEM_CURRENCIES = ("USD", "EUR", "GBP")


def main() -> None:
    init_db()
    with Session(engine) as session:
        accounts = AccountRepository(session)
        ledger = LedgerService(session, accounts)

        for name, ubo, country, currency, opening in DEMO_ENTITIES:
            entity = session.exec(
                select(Entity).where(Entity.legal_name == name, Entity.ubo_tax_id == ubo)
            ).first()
            if entity is None:
                entity = Entity(legal_name=name, ubo_tax_id=ubo, country=country)
                session.add(entity)
                session.commit()
                session.refresh(entity)
            account = accounts.find_entity_account(entity.id, currency)
            if account is None:
                account = accounts.create(
                    Account(
                        entity_id=entity.id,
                        account_type=AccountType.USER_ACCOUNT,
                        currency=currency,
                    )
                )
            if opening > 0 and account.balance == 0:
                clearing = accounts.get_or_create_system_account(
                    AccountType.ESCROWRAIL_CLEARING_ACCOUNT, currency
                )
                ledger.record_transfer(
                    clearing.id,
                    account.id,
                    opening,
                    currency,
                    "Seed opening balance",
                )
            print(f"{name} ({ubo}) {currency} account {account.id}")

        for currency in SYSTEM_CURRENCIES:
            for account_type in (
                AccountType.SAFEGUARDED_ESCROW_VAULT,
                AccountType.ESCROWRAIL_FEE_ACCOUNT,
            ):
                account = accounts.get_or_create_system_account(account_type, currency)
                session.commit()
                print(f"{account_type.value} {currency}: {account.id}")


if __name__ == "__main__":
    main()
