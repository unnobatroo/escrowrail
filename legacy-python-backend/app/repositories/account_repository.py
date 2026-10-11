"""Data access for entities and accounts. No business rules here."""

import uuid

from sqlmodel import Session, select

from app.models.entities import Account, AccountType, Entity


class EntityRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def get(self, entity_id: uuid.UUID) -> Entity | None:
        return self.session.get(Entity, entity_id)

    def create(self, entity: Entity) -> Entity:
        self.session.add(entity)
        self.session.commit()
        self.session.refresh(entity)
        return entity


class AccountRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def get(self, account_id: uuid.UUID) -> Account | None:
        return self.session.get(Account, account_id)

    def get_for_update(self, account_id: uuid.UUID) -> Account | None:
        stmt = select(Account).where(Account.id == account_id).with_for_update()
        return self.session.exec(stmt).first()

    def find_entity_account(self, entity_id: uuid.UUID, currency: str) -> Account | None:
        stmt = select(Account).where(
            Account.entity_id == entity_id,
            Account.currency == currency,
            Account.account_type == AccountType.USER_ACCOUNT,
        )
        return self.session.exec(stmt).first()

    def get_or_create_system_account(self, account_type: AccountType, currency: str) -> Account:
        stmt = select(Account).where(
            Account.entity_id.is_(None),  # type: ignore[union-attr]
            Account.account_type == account_type,
            Account.currency == currency,
        )
        account = self.session.exec(stmt).first()
        if account is None:
            account = Account(entity_id=None, account_type=account_type, currency=currency)
            self.session.add(account)
            self.session.flush()
        return account

    def create(self, account: Account) -> Account:
        self.session.add(account)
        self.session.commit()
        self.session.refresh(account)
        return account
