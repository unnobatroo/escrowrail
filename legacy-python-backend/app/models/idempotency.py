"""Idempotency and webhook delivery audit models."""

import uuid
from datetime import UTC, datetime

from sqlmodel import Field, SQLModel

# Sentinel stored in IdempotencyRecord.status_code while a request is
# still executing — pollers wait for it to become a real HTTP code.
IN_FLIGHT = -1


class IdempotencyRecord(SQLModel, table=True):
    __tablename__ = "idempotency_records"

    id: str = Field(default_factory=lambda: str(uuid.uuid4()), primary_key=True)
    idempotency_key: str = Field(index=True, unique=True, nullable=False)
    endpoint: str = Field(nullable=False)
    status_code: int = Field(nullable=False)
    response_body: str = Field(nullable=False)
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))


class WebhookDeliveryLog(SQLModel, table=True):
    __tablename__ = "webhook_delivery_logs"

    id: str = Field(default_factory=lambda: str(uuid.uuid4()), primary_key=True)
    provider: str = Field(nullable=False)
    event_type: str = Field(nullable=False)
    payload_hash: str = Field(index=True, nullable=False)
    raw_payload: str = Field(nullable=False)
    processed_status: str = Field(nullable=False)  # SUCCESS, DUPLICATE, FAILED
    error_message: str | None = Field(default=None)
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
