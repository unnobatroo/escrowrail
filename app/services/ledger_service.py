"""Double-entry ledger service.

Owns balance integrity: every monetary mutation goes through a balanced
LedgerTransaction persisted atomically with the balance updates.
"""

import logging
import uuid
from decimal import Decimal

from sqlmodel import Session

from app.exceptions import (
    CurrencyMismatchError,
    InsufficientFundsError,
    InvalidAmountError,
    NotFoundError,
)
from app.models.entities import AccountType
from app.models.ledger import LedgerTransaction
from app.repositories.account_repository import AccountRepository

logger = logging.getLogger("wise_escrow.ledger")

CENT = Decimal("0.01")

# Accounts whose balance may never go negative. Wise-owned clearing/fee
# accounts represent the external-rails boundary and may run a net position.
NON_NEGATIVE_TYPES = {
    AccountType.USER_ACCOUNT,
    AccountType.SAFEGUARDED_ESCROW_VAULT,
}


class LedgerService:
    def __init__(self, session: Session, accounts: AccountRepository) -> None:
        self.session = session
        self.accounts = accounts

    def record_transfer(
        self,
        debit_account_id: uuid.UUID,
        credit_account_id: uuid.UUID,
        amount: Decimal,
        currency: str,
        description: str,
        contract_id: uuid.UUID | None = None,
    ) -> LedgerTransaction:
        """Execute one balanced double-entry leg atomically."""
        if not isinstance(amount, Decimal):
            raise InvalidAmountError(
                "Amount must be a Decimal", context={"type": type(amount).__name__}
            )
        amount = amount.quantize(CENT)
        if amount <= 0:
            raise InvalidAmountError(
                "Transfer amount must be positive",
                context={"amount": str(amount)},
            )
        currency = currency.upper()
        try:
            debit = self.accounts.get_for_update(debit_account_id)
            credit = self.accounts.get_for_update(credit_account_id)
            if debit is None or credit is None:
                raise NotFoundError(
                    "Account not found",
                    context={
                        "debit_account_id": str(debit_account_id),
                        "credit_account_id": str(credit_account_id),
                    },
                )
            if debit.currency != currency or credit.currency != currency:
                raise CurrencyMismatchError(
                    "Ledger leg currency does not match account currency",
                    context={
                        "leg_currency": currency,
                        "debit_currency": debit.currency,
                        "credit_currency": credit.currency,
                    },
                )
            if debit.account_type in NON_NEGATIVE_TYPES and debit.balance < amount:
                raise InsufficientFundsError(
                    "Insufficient funds",
                    context={
                        "account_id": str(debit.id),
                        "balance": str(debit.balance),
                        "required": str(amount),
                    },
                )
            debit.balance -= amount
            credit.balance += amount
            tx = LedgerTransaction(
                contract_id=contract_id,
                debit_account_id=debit.id,
                credit_account_id=credit.id,
                amount=amount,
                currency=currency,
                description=description,
            )
            self.session.add_all([debit, credit, tx])
            self.session.commit()
            self.session.refresh(tx)
        except Exception:
            self.session.rollback()
            raise
        logger.info(
            "ledger_transfer_recorded",
            extra={
                "ledger_tx_id": str(tx.id),
                "contract_id": str(contract_id) if contract_id else None,
                "debit_account_id": str(debit.id),
                "credit_account_id": str(credit.id),
                "amount": str(amount),
                "currency": currency,
            },
        )
        return tx
