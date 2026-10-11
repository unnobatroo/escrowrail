"""Entity and account models.

Balances are Decimal (Numeric) columns — never float.
"""

import uuid
from datetime import UTC, datetime
from decimal import Decimal
from enum import StrEnum

from sqlalchemy import Column, Numeric
from sqlmodel import Field, SQLModel

MONEY = Numeric(20, 2)


class AccountType(StrEnum):
    USER_ACCOUNT = "USER_ACCOUNT"
    SAFEGUARDED_ESCROW_VAULT = "SAFEGUARDED_ESCROW_VAULT"
    WISE_FEE_ACCOUNT = "WISE_FEE_ACCOUNT"
    # Wise-owned settlement float per currency. Receives source-currency
    # deposits and supplies target-currency conversions; may run negative
    # (net position vs. external bank rails).
    WISE_CLEARING_ACCOUNT = "WISE_CLEARING_ACCOUNT"


class Entity(SQLModel, table=True):
    __tablename__ = "entities"

    id: uuid.UUID = Field(default_factory=uuid.uuid4, primary_key=True)
    legal_name: str
    ubo_tax_id: str = Field(index=True)
    country: str
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))


class Account(SQLModel, table=True):
    __tablename__ = "accounts"

    id: uuid.UUID = Field(default_factory=uuid.uuid4, primary_key=True)
    entity_id: uuid.UUID | None = Field(default=None, foreign_key="entities.id")
    account_type: AccountType
    currency: str = Field(max_length=3, index=True)
    balance: Decimal = Field(default=Decimal("0"), sa_column=Column(MONEY))
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
