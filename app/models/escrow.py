"""Escrow contract model and state machine enums."""

import uuid
from datetime import UTC, datetime
from decimal import Decimal
from enum import StrEnum

from sqlalchemy import Column, Numeric
from sqlmodel import Field, SQLModel

from app.models.entities import MONEY

RATE = Numeric(20, 8)


class ContractStatus(StrEnum):
    INITIATED = "INITIATED"
    FUNDED = "FUNDED"
    SETTLED = "SETTLED"
    CANCELLED = "CANCELLED"


class TrackingType(StrEnum):
    GITHUB_PR = "GITHUB_PR"
    SHIPPING_CARRIER = "SHIPPING_CARRIER"


class EscrowContract(SQLModel, table=True):
    __tablename__ = "escrow_contracts"

    id: uuid.UUID = Field(default_factory=uuid.uuid4, primary_key=True)
    buyer_id: uuid.UUID = Field(foreign_key="entities.id")
    seller_id: uuid.UUID = Field(foreign_key="entities.id")
    target_currency: str = Field(max_length=3)
    target_amount: Decimal = Field(sa_column=Column(MONEY))
    source_currency: str = Field(max_length=3)
    deposit_amount: Decimal | None = Field(default=None, sa_column=Column(MONEY))
    locked_spot_rate: Decimal | None = Field(default=None, sa_column=Column(RATE))
    # Target-currency amount actually vaulted at funding time. Stored
    # rather than recomputed: rate column precision would drift the math.
    escrowed_amount: Decimal | None = Field(default=None, sa_column=Column(MONEY))
    status: ContractStatus = Field(default=ContractStatus.INITIATED)
    tracking_type: TrackingType
    tracking_key: str = Field(index=True)
    # Originating account for asymmetric cancellation refunds.
    buyer_account_id: uuid.UUID | None = Field(default=None, foreign_key="accounts.id")
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
