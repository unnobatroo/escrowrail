"""Double-entry ledger model.

Each LedgerTransaction records one balanced leg: a debit and a credit of
equal amount in a single currency. Multi-currency flows are decomposed
into same-currency legs routed through Escrowrail clearing accounts.
"""

import uuid
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import Column
from sqlmodel import Field, SQLModel

from app.models.entities import MONEY


class LedgerTransaction(SQLModel, table=True):
    __tablename__ = "ledger_transactions"

    id: uuid.UUID = Field(default_factory=uuid.uuid4, primary_key=True)
    contract_id: uuid.UUID | None = Field(
        default=None, foreign_key="escrow_contracts.id", index=True
    )
    debit_account_id: uuid.UUID = Field(foreign_key="accounts.id")
    credit_account_id: uuid.UUID = Field(foreign_key="accounts.id")
    amount: Decimal = Field(sa_column=Column(MONEY))
    currency: str = Field(max_length=3)
    description: str
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
