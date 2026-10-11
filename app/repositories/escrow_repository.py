"""Data access for escrow contracts and ledger entries."""

import uuid

from sqlmodel import Session, select

from app.models.escrow import ContractStatus, EscrowContract, TrackingType
from app.models.ledger import LedgerTransaction


class EscrowRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def get(self, contract_id: uuid.UUID) -> EscrowContract | None:
        return self.session.get(EscrowContract, contract_id)

    def get_for_update(self, contract_id: uuid.UUID) -> EscrowContract | None:
        """Fetch the contract under a row-level write lock (FOR UPDATE on
        PostgreSQL; SQLite serializes via BEGIN IMMEDIATE)."""
        stmt = select(EscrowContract).where(EscrowContract.id == contract_id).with_for_update()
        return self.session.exec(stmt).first()

    def save(self, contract: EscrowContract) -> EscrowContract:
        """Stage a mutation inside the caller's transaction (flush only —
        the service layer owns the commit boundary)."""
        self.session.add(contract)
        self.session.flush()
        return contract

    def create(self, contract: EscrowContract) -> EscrowContract:
        self.session.add(contract)
        self.session.commit()
        self.session.refresh(contract)
        return contract

    def update(self, contract: EscrowContract) -> EscrowContract:
        self.session.add(contract)
        self.session.commit()
        self.session.refresh(contract)
        return contract

    def find_funded_by_tracking(
        self, tracking_type: TrackingType, tracking_key: str
    ) -> EscrowContract | None:
        stmt = select(EscrowContract).where(
            EscrowContract.tracking_type == tracking_type,
            EscrowContract.tracking_key == tracking_key,
            EscrowContract.status == ContractStatus.FUNDED,
        )
        return self.session.exec(stmt).first()

    def ledger_entries(self, contract_id: uuid.UUID) -> list[LedgerTransaction]:
        stmt = (
            select(LedgerTransaction)
            .where(LedgerTransaction.contract_id == contract_id)
            .order_by(LedgerTransaction.created_at)  # type: ignore[arg-type]
        )
        return list(self.session.exec(stmt).all())
