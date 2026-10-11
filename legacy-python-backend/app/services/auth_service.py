"""Tenant onboarding and JWT issuance/verification.

Registration is one atomic unit: Entity (org) + User + zeroed USD/EUR
accounts via LedgerService boot. Passwords are argon2id-hashed via
pwdlib; tokens are HS256 JWTs carrying the user id.
"""

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import jwt
from pwdlib import PasswordHash
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session

from app.config import settings
from app.exceptions import DuplicateEmailError, UnauthorizedError
from app.models.auth import User
from app.models.entities import Account, AccountType, Entity
from app.repositories.account_repository import AccountRepository, EntityRepository
from app.repositories.user_repository import UserRepository
from app.services.ledger_service import LedgerService

_password_hash = PasswordHash.recommended()

SANDBOX_USD = Decimal("50000.00")
SANDBOX_EUR = Decimal("10000.00")


class AuthService:
    def __init__(
        self,
        session: Session,
        users: UserRepository,
        entities: EntityRepository,
        accounts: AccountRepository,
        ledger: LedgerService,
    ) -> None:
        self.session = session
        self.users = users
        self.entities = entities
        self.accounts = accounts
        self.ledger = ledger

    def register(
        self,
        email: str,
        password: str,
        legal_name: str,
        ubo_tax_id: str,
        country: str,
    ) -> tuple[User, Entity]:
        email = email.lower().strip()
        if self.users.find_by_email(email) is not None:
            raise DuplicateEmailError("Email is already registered", context={"email": email})
        try:
            entity = Entity(legal_name=legal_name, ubo_tax_id=ubo_tax_id, country=country)
            self.session.add(entity)
            self.session.flush()
            user = User(
                email=email,
                hashed_password=_password_hash.hash(password),
                entity_id=entity.id,
            )
            self.session.add(user)
            self.session.flush()
            # Boot operating accounts in both settlement currencies. The
            # simulated EMI rails credit a sandbox balance through the ledger
            # so the account starts double-entry funded, not hand-set.
            for currency, opening in (("USD", SANDBOX_USD), ("EUR", SANDBOX_EUR)):
                account = Account(
                    entity_id=entity.id,
                    account_type=AccountType.USER_ACCOUNT,
                    currency=currency,
                )
                self.session.add(account)
                self.session.flush()
                clearing = self.accounts.get_or_create_system_account(
                    AccountType.WISE_CLEARING_ACCOUNT, currency
                )
                self.ledger.record_transfer(
                    clearing.id,
                    account.id,
                    opening,
                    currency,
                    "Sandbox opening balance",
                    commit=False,
                )
            self.session.commit()
        except IntegrityError as exc:
            self.session.rollback()
            raise DuplicateEmailError(
                "Email is already registered", context={"email": email}
            ) from exc
        self.session.refresh(user)
        return user, entity

    def authenticate(self, email: str, password: str) -> User:
        user = self.users.find_by_email(email.lower().strip())
        if user is None or not _password_hash.verify(password, user.hashed_password):
            raise UnauthorizedError("Invalid email or password", context={"email": email})
        if not user.is_active:
            raise UnauthorizedError("Account is deactivated", context={})
        return user

    @staticmethod
    def issue_token(user: User) -> str:
        now = datetime.now(UTC)
        payload = {
            "sub": str(user.id),
            "iat": int(now.timestamp()),
            "exp": int((now + timedelta(minutes=settings.jwt_expire_minutes)).timestamp()),
        }
        return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)

    @staticmethod
    def decode_access_token(token: str) -> uuid.UUID:
        try:
            payload = jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
            return uuid.UUID(str(payload["sub"]))
        except (jwt.PyJWTError, KeyError, ValueError) as exc:
            raise UnauthorizedError("Invalid or expired token", context={}) from exc

    def resolve_token(self, token: str) -> User:
        user = self.users.get(self.decode_access_token(token))
        if user is None or not user.is_active:
            raise UnauthorizedError("Invalid or expired token", context={})
        return user
