"""Typed domain exception hierarchy.

All business-rule violations raise a subclass of ``WiseEscrowException``.
The global FastAPI handler in ``app.main`` translates them into uniform
JSON error responses; services never raise HTTPException directly.
"""

from typing import Any


class WiseEscrowException(Exception):
    """Base class for all domain errors."""

    error_code = "ERR_WISE_ESCROW"
    http_status = 400
    client_code: str | None = None

    def __init__(self, message: str, context: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.context = context or {}


class CircularOwnershipError(WiseEscrowException):
    error_code = "ERR_CIRCULAR_OWNERSHIP"
    http_status = 403
    client_code = "CIRCULAR_OWNERSHIP_DETECTED"


class InvalidAmountError(WiseEscrowException):
    error_code = "ERR_INVALID_AMOUNT"
    http_status = 422


class InsufficientFundsError(WiseEscrowException):
    error_code = "ERR_INSUFFICIENT_FUNDS"
    http_status = 422


class InvalidStateTransitionError(WiseEscrowException):
    error_code = "ERR_INVALID_STATE_TRANSITION"
    http_status = 409


class CurrencyMismatchError(WiseEscrowException):
    error_code = "ERR_CURRENCY_MISMATCH"
    http_status = 422


class NotFoundError(WiseEscrowException):
    error_code = "ERR_NOT_FOUND"
    http_status = 404


class UnsupportedCurrencyPairError(WiseEscrowException):
    error_code = "ERR_UNSUPPORTED_CURRENCY_PAIR"
    http_status = 422


class WebhookValidationError(WiseEscrowException):
    error_code = "ERR_WEBHOOK_VALIDATION"
    http_status = 400
