"""Seed demo data for a live Wise Smart Escrow demonstration.

Run:  python scripts/seed_demo_data.py
Creates demo entities/accounts in the configured database (default
sqlite:///./wise_escrow.db). Idempotent: skips entities that already exist.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from sqlmodel import Session, select

from app.database import engine, init_db
from app.models.entities import AccountType, Entity
from app.repositories.account_repository import AccountRepository, EntityRepository
from app.repositories.user_repository import UserRepository
from app.services.auth_service import AuthService
from app.services.ledger_service import LedgerService

DEMO_USERS = [
    # email, password, org name, ubo_tax_id, country
    ("buyer@acme.test", "demo-password-1", "Acme Hardware US", "TAX-US-1001", "US"),
    ("seller@berlin.test", "demo-password-1", "Berlin Robotics GmbH", "TAX-DE-2002", "DE"),
    # Shares Acme's UBO -> demonstrates circular-ownership rejection.
    ("sub@acme-de.test", "demo-password-1", "Acme Subsidiary DE", "TAX-US-1001", "DE"),
]

SYSTEM_CURRENCIES = ("USD", "EUR", "GBP")


def main() -> None:
    init_db()
    with Session(engine) as session:
        accounts = AccountRepository(session)
        users = UserRepository(session)
        ledger = LedgerService(session, accounts)
        auth = AuthService(session, users, EntityRepository(session), accounts, ledger)

        for email, password, name, ubo, country in DEMO_USERS:
            if users.find_by_email(email) is not None:
                entity = session.exec(
                    select(Entity).where(Entity.legal_name == name, Entity.ubo_tax_id == ubo)
                ).first()
                print(f"{name}: user {email} already exists")
                continue
            _user, entity = auth.register(
                email=email,
                password=password,
                legal_name=name,
                ubo_tax_id=ubo,
                country=country,
            )
            print(f"{name} ({ubo}) -> user {email}, entity {entity.id}")

        for currency in SYSTEM_CURRENCIES:
            for account_type in (
                AccountType.SAFEGUARDED_ESCROW_VAULT,
                AccountType.WISE_FEE_ACCOUNT,
            ):
                account = accounts.get_or_create_system_account(account_type, currency)
                session.commit()
                print(f"{account_type.value} {currency}: {account.id}")


if __name__ == "__main__":
    main()
