"""Ledger integrity invariant computation.

Replays every ledger leg and compares reconstructed balances against
stored balances. In a correct system the discrepancy is always 0 —
used by /health/ready and the ledger_invariant_discrepancy_cents gauge.
"""

from decimal import Decimal

from sqlmodel import Session, select

from app.models.entities import Account
from app.models.ledger import LedgerTransaction


def compute_discrepancy_cents(session: Session) -> int:
    """Total absolute cent discrepancy between stored balances and
    ledger replay. Must strictly remain 0."""
    balances: dict[object, Decimal] = {}
    for tx in session.exec(select(LedgerTransaction)).all():
        balances[tx.debit_account_id] = balances.get(tx.debit_account_id, Decimal("0")) - tx.amount
        balances[tx.credit_account_id] = (
            balances.get(tx.credit_account_id, Decimal("0")) + tx.amount
        )
    discrepancy = Decimal("0")
    for account in session.exec(select(Account)).all():
        expected = balances.get(account.id, Decimal("0"))
        discrepancy += abs(account.balance - expected)
    return int((discrepancy * 100).to_integral_value())
