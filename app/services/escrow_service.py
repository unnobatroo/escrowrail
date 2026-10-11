"""Escrow contract lifecycle orchestration.

Coordinates the contract state machine, UBO anti-collusion checks,
immediate spot conversion, programmatic release and asymmetric
cancellation. All fund movement is delegated to LedgerService.
"""

import logging
import uuid
from decimal import Decimal

from sqlmodel import Session

from app.exceptions import (
    CircularOwnershipError,
    InsufficientFundsError,
    InvalidAmountError,
    InvalidStateTransitionError,
    NotFoundError,
    WiseEscrowException,
)
from app.models.entities import AccountType
from app.models.escrow import ContractStatus, EscrowContract, TrackingType
from app.repositories.account_repository import AccountRepository, EntityRepository
from app.repositories.escrow_repository import EscrowRepository
from app.services.fx_service import IFXRateProvider
from app.services.ledger_service import CENT, LedgerService

logger = logging.getLogger("escrowrail_escrow.escrow")


class EscrowService:
    CONVERSION_FEE_RATE = Decimal("0.005")  # 0.5% upfront, source currency
    ESCROW_FEE_RATE = Decimal("0.008")  # 0.8% on release, target currency

    def __init__(
        self,
        session: Session,
        entities: EntityRepository,
        accounts: AccountRepository,
        contracts: EscrowRepository,
        ledger: LedgerService,
        fx: IFXRateProvider,
    ) -> None:
        self.session = session
        self.entities = entities
        self.accounts = accounts
        self.contracts = contracts
        self.ledger = ledger
        self.fx = fx

    def create_contract(
        self,
        buyer_id: uuid.UUID,
        seller_id: uuid.UUID,
        target_currency: str,
        target_amount: Decimal,
        source_currency: str,
        tracking_type: TrackingType,
        tracking_key: str,
    ) -> EscrowContract:
        buyer = self.entities.get(buyer_id)
        seller = self.entities.get(seller_id)
        if buyer is None or seller is None:
            raise NotFoundError(
                "Buyer or seller entity not found",
                context={
                    "buyer_id": str(buyer_id),
                    "seller_id": str(seller_id),
                },
            )
        if buyer.ubo_tax_id == seller.ubo_tax_id:
            logger.warning(
                "circular_ownership_detected",
                extra={
                    "buyer_id": str(buyer_id),
                    "seller_id": str(seller_id),
                    "ubo_tax_id": buyer.ubo_tax_id,
                },
            )
            raise CircularOwnershipError(
                "Buyer and seller share the same ultimate beneficial owner",
                context={
                    "buyer_id": str(buyer_id),
                    "seller_id": str(seller_id),
                    "ubo_tax_id": buyer.ubo_tax_id,
                },
            )
        if target_amount <= 0:
            raise InvalidAmountError(
                "Target amount must be positive",
                context={"target_amount": str(target_amount)},
            )
        # Fail early if the FX pair is unsupported.
        self.fx.get_rate(source_currency, target_currency)
        contract = EscrowContract(
            buyer_id=buyer_id,
            seller_id=seller_id,
            target_currency=target_currency.upper(),
            target_amount=target_amount,
            source_currency=source_currency.upper(),
            tracking_type=tracking_type,
            tracking_key=tracking_key,
        )
        created = self.contracts.create(contract)
        logger.info(
            "contract_created",
            extra={"contract_id": str(created.id), "tracking_key": tracking_key},
        )
        return created

    def fund_contract(
        self,
        contract_id: uuid.UUID,
        account_id: uuid.UUID,
        deposit_amount: Decimal,
    ) -> EscrowContract:
        """Convert first, then freeze: pull the spot rate now, collect the
        0.5% conversion fee in source currency, and land converted target
        funds directly in the safeguarded vault."""
        contract = self._require_contract(contract_id)
        self._require_status(contract, ContractStatus.INITIATED)
        account = self.accounts.get(account_id)
        if account is None:
            raise NotFoundError(
                "Funding account not found",
                context={"account_id": str(account_id)},
            )
        if account.entity_id != contract.buyer_id or (
            account.account_type != AccountType.USER_ACCOUNT
        ):
            raise WiseEscrowException(
                "Funding account must be the buyer's user account",
                context={"account_id": str(account_id)},
            )
        if account.currency != contract.source_currency:
            raise WiseEscrowException(
                "Funding account currency must match contract source currency",
                context={
                    "account_currency": account.currency,
                    "source_currency": contract.source_currency,
                },
            )
        if deposit_amount <= 0:
            raise InvalidAmountError(
                "Deposit amount must be positive",
                context={"deposit_amount": str(deposit_amount)},
            )
        if account.balance < deposit_amount:
            raise InsufficientFundsError(
                "Buyer balance cannot cover the deposit",
                context={
                    "account_id": str(account.id),
                    "balance": str(account.balance),
                    "required": str(deposit_amount),
                },
            )

        rate = self.fx.get_rate(contract.source_currency, contract.target_currency)
        fee = self._conversion_fee(deposit_amount)
        converted = ((deposit_amount - fee) * rate).quantize(CENT)
        if converted < contract.target_amount:
            raise InsufficientFundsError(
                "Converted deposit does not cover the target amount",
                context={
                    "converted": str(converted),
                    "target_amount": str(contract.target_amount),
                    "spot_rate": str(rate),
                },
            )

        source = contract.source_currency
        target = contract.target_currency
        fee_account = self.accounts.get_or_create_system_account(
            AccountType.ESCROWRAIL_FEE_ACCOUNT, source
        )
        clearing_source = self.accounts.get_or_create_system_account(
            AccountType.ESCROWRAIL_CLEARING_ACCOUNT, source
        )
        clearing_target = self.accounts.get_or_create_system_account(
            AccountType.ESCROWRAIL_CLEARING_ACCOUNT, target
        )
        vault = self.accounts.get_or_create_system_account(
            AccountType.SAFEGUARDED_ESCROW_VAULT, target
        )
        cid = contract.id
        self.ledger.record_transfer(
            account.id,
            fee_account.id,
            fee,
            source,
            "0.5% upfront conversion fee",
            contract_id=cid,
        )
        self.ledger.record_transfer(
            account.id,
            clearing_source.id,
            deposit_amount - fee,
            source,
            "Source deposit into Escrowrail clearing",
            contract_id=cid,
        )
        self.ledger.record_transfer(
            clearing_target.id,
            vault.id,
            converted,
            target,
            "Spot conversion into safeguarded vault",
            contract_id=cid,
        )

        contract.deposit_amount = deposit_amount
        contract.locked_spot_rate = rate
        contract.buyer_account_id = account.id
        contract.status = ContractStatus.FUNDED
        updated = self.contracts.update(contract)
        logger.info(
            "contract_funded",
            extra={
                "contract_id": str(cid),
                "deposit_amount": str(deposit_amount),
                "converted": str(converted),
                "locked_spot_rate": str(rate),
            },
        )
        return updated

    def settle_milestone(self, contract_id: uuid.UUID) -> EscrowContract:
        """Release escrowed funds after a validated webhook: 0.8% escrow
        fee to Escrowrail, remaining 99.2% to the seller's active account."""
        contract = self._require_contract(contract_id)
        self._require_status(contract, ContractStatus.FUNDED)
        seller_account = self.accounts.find_entity_account(
            contract.seller_id, contract.target_currency
        )
        if seller_account is None:
            raise NotFoundError(
                "Seller has no active account in the target currency",
                context={
                    "seller_id": str(contract.seller_id),
                    "currency": contract.target_currency,
                },
            )
        escrowed = self._escrowed_amount(contract)
        fee = (escrowed * self.ESCROW_FEE_RATE).quantize(CENT)
        target = contract.target_currency
        vault = self.accounts.get_or_create_system_account(
            AccountType.SAFEGUARDED_ESCROW_VAULT, target
        )
        fee_account = self.accounts.get_or_create_system_account(
            AccountType.ESCROWRAIL_FEE_ACCOUNT, target
        )
        cid = contract.id
        self.ledger.record_transfer(
            vault.id,
            fee_account.id,
            fee,
            target,
            "0.8% escrow software fee",
            contract_id=cid,
        )
        self.ledger.record_transfer(
            vault.id,
            seller_account.id,
            escrowed - fee,
            target,
            "Escrow release to seller",
            contract_id=cid,
        )
        contract.status = ContractStatus.SETTLED
        updated = self.contracts.update(contract)
        logger.info(
            "contract_settled",
            extra={
                "contract_id": str(cid),
                "escrowed": str(escrowed),
                "fee": str(fee),
                "seller_account_id": str(seller_account.id),
            },
        )
        return updated

    def cancel_contract(self, contract_id: uuid.UUID) -> EscrowContract:
        """Asymmetric cancellation: liquidate target funds back to the
        source currency at the latest spot. The buyer absorbs negative FX
        movement; Escrowrail retains the original conversion fee; funds return
        exclusively to the originating account."""
        contract = self._require_contract(contract_id)
        if contract.status == ContractStatus.INITIATED:
            contract.status = ContractStatus.CANCELLED
            return self.contracts.update(contract)
        self._require_status(contract, ContractStatus.FUNDED)
        if contract.buyer_account_id is None:
            raise WiseEscrowException(
                "Contract has no originating account to refund",
                context={"contract_id": str(contract_id)},
            )
        escrowed = self._escrowed_amount(contract)
        rate_now = self.fx.get_rate(contract.target_currency, contract.source_currency)
        refund = (escrowed * rate_now).quantize(CENT)

        target = contract.target_currency
        source = contract.source_currency
        vault = self.accounts.get_or_create_system_account(
            AccountType.SAFEGUARDED_ESCROW_VAULT, target
        )
        clearing_target = self.accounts.get_or_create_system_account(
            AccountType.ESCROWRAIL_CLEARING_ACCOUNT, target
        )
        clearing_source = self.accounts.get_or_create_system_account(
            AccountType.ESCROWRAIL_CLEARING_ACCOUNT, source
        )
        cid = contract.id
        self.ledger.record_transfer(
            vault.id,
            clearing_target.id,
            escrowed,
            target,
            "Vault liquidation on cancellation",
            contract_id=cid,
        )
        self.ledger.record_transfer(
            clearing_source.id,
            contract.buyer_account_id,
            refund,
            source,
            "Refund to originating account at latest spot",
            contract_id=cid,
        )
        contract.status = ContractStatus.CANCELLED
        updated = self.contracts.update(contract)
        logger.info(
            "contract_cancelled",
            extra={
                "contract_id": str(cid),
                "escrowed": str(escrowed),
                "refund": str(refund),
                "spot_rate": str(rate_now),
            },
        )
        return updated

    def get_contract(self, contract_id: uuid.UUID) -> EscrowContract:
        return self._require_contract(contract_id)

    def _require_contract(self, contract_id: uuid.UUID) -> EscrowContract:
        contract = self.contracts.get(contract_id)
        if contract is None:
            raise NotFoundError(
                "Escrow contract not found",
                context={"contract_id": str(contract_id)},
            )
        return contract

    @staticmethod
    def _require_status(contract: EscrowContract, expected: ContractStatus) -> None:
        if contract.status != expected:
            raise InvalidStateTransitionError(
                f"Contract is {contract.status.value}, expected {expected.value}",
                context={
                    "contract_id": str(contract.id),
                    "status": contract.status.value,
                    "expected": expected.value,
                },
            )

    def _conversion_fee(self, deposit_amount: Decimal) -> Decimal:
        return (deposit_amount * self.CONVERSION_FEE_RATE).quantize(CENT)

    def _escrowed_amount(self, contract: EscrowContract) -> Decimal:
        """Recompute the vaulted amount deterministically from the stored
        deposit and locked spot rate (identical math to fund_contract)."""
        if contract.deposit_amount is None or contract.locked_spot_rate is None:
            raise WiseEscrowException(
                "Contract is not funded",
                context={"contract_id": str(contract.id)},
            )
        fee = self._conversion_fee(contract.deposit_amount)
        return ((contract.deposit_amount - fee) * contract.locked_spot_rate).quantize(CENT)
