package com.wise.escrow.application.service;

import com.wise.escrow.application.dto.Events.ContractCancelled;
import com.wise.escrow.application.dto.Events.ContractFunded;
import com.wise.escrow.application.dto.Events.ContractSettled;
import com.wise.escrow.domain.exception.CircularOwnershipException;
import com.wise.escrow.domain.exception.ForbiddenException;
import com.wise.escrow.domain.exception.InsufficientFundsException;
import com.wise.escrow.domain.exception.InvalidStateTransitionException;
import com.wise.escrow.domain.exception.NotFoundException;
import com.wise.escrow.domain.model.Account;
import com.wise.escrow.domain.model.AccountType;
import com.wise.escrow.domain.model.EscrowContract;
import com.wise.escrow.domain.model.EscrowStatus;
import com.wise.escrow.domain.model.MoneyAmounts;
import com.wise.escrow.domain.model.Organization;
import com.wise.escrow.domain.model.TrackingType;
import com.wise.escrow.domain.repository.AccountRepository;
import com.wise.escrow.domain.repository.AppUserRepository;
import com.wise.escrow.domain.repository.EscrowContractRepository;
import com.wise.escrow.domain.repository.LedgerRepository;
import com.wise.escrow.domain.repository.LedgerRepository.LedgerEntry;
import com.wise.escrow.domain.repository.OrganizationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.money.MonetaryAmount;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escrow orchestration. All state changes run inside a single transaction
 * that holds PESSIMISTIC_WRITE locks on the contract row and every account
 * row the ledger legs touch; domain events are published through the
 * Modulith outbox in the same transaction.
 */
@Service
public class EscrowService {

    /** Wise conversion fee, taken in source currency at funding time. */
    public static final BigDecimal CONVERSION_FEE_RATE = new BigDecimal("0.005");

    /** Settlement take rate, taken in target currency from the vault. */
    public static final BigDecimal SETTLEMENT_FEE_RATE = new BigDecimal("0.008");

    private static final BigDecimal CENT = new BigDecimal("0.01");

    private final EscrowContractRepository contracts;
    private final AccountRepository accounts;
    private final OrganizationRepository organizations;
    private final AppUserRepository users;
    private final LedgerRepository ledger;
    private final LedgerService ledgerService;
    private final FxRateProvider fx;
    private final ApplicationEventPublisher events;

    public EscrowService(
            EscrowContractRepository contracts,
            AccountRepository accounts,
            OrganizationRepository organizations,
            AppUserRepository users,
            LedgerRepository ledger,
            LedgerService ledgerService,
            FxRateProvider fx,
            ApplicationEventPublisher events) {
        this.contracts = contracts;
        this.accounts = accounts;
        this.organizations = organizations;
        this.users = users;
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.fx = fx;
        this.events = events;
    }

    // ---------- reads (tenant-scoped) ----------

    @Transactional(readOnly = true)
    public EscrowContract getForEntity(UUID contractId, UUID entityId) {
        return contracts
                .findByIdForEntity(contractId, entityId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
    }

    @Transactional(readOnly = true)
    public List<EscrowContract> listForEntity(UUID entityId) {
        return contracts.findAllForEntity(entityId);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> auditForEntity(UUID contractId, UUID entityId) {
        getForEntity(contractId, entityId); // tenancy gate before the trail
        return ledger.findByContractIdOrderByCreatedAt(contractId);
    }

    /** Counterparty resolution for the invite flow. Empty if unregistered. */
    @Transactional(readOnly = true)
    public Optional<Counterparty> resolveCounterparty(String email, UUID requesterEntityId) {
        return users.findByEmail(email)
                .flatMap(u -> organizations.findById(u.entityId()))
                .map(org -> new Counterparty(
                        org,
                        organizations
                                .findById(requesterEntityId)
                                .map(me -> me.uboTaxId().equals(org.uboTaxId()))
                                .orElse(false)));
    }

    public record Counterparty(Organization organization, boolean sameUbo) {}

    // ---------- lifecycle ----------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract createContract(
            UUID buyerEntityId,
            UUID sellerEntityId,
            MonetaryAmount targetAmount,
            String sourceCurrency,
            TrackingType trackingType,
            String trackingKey) {
        Organization buyer = organizations
                .findById(buyerEntityId)
                .orElseThrow(() -> new NotFoundException("Buyer organization not found"));
        Organization seller = organizations
                .findById(sellerEntityId)
                .orElseThrow(() -> new NotFoundException("Seller organization not found"));
        if (buyer.uboTaxId().equals(seller.uboTaxId())) {
            throw new CircularOwnershipException(
                    "Buyer and seller share UBO tax id " + buyer.uboTaxId().value());
        }
        return contracts.save(EscrowContract.create(
                buyerEntityId, sellerEntityId, targetAmount, sourceCurrency, trackingType, trackingKey));
    }

    /**
     * Buyer funds the contract: 0.5% conversion fee in source currency,
     * remainder converted at spot and vaulted in target currency.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract fundContract(UUID contractId, UUID actorEntityId, MonetaryAmount deposit) {
        EscrowContract contract = contracts
                .findByIdForUpdate(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
        if (!contract.involves(actorEntityId)) {
            throw new NotFoundException("Contract not found: " + contractId);
        }
        if (!contract.isBuyer(actorEntityId)) {
            throw new ForbiddenException("Only the buyer may fund this contract");
        }
        if (contract.status() != EscrowStatus.INITIATED) {
            throw new InvalidStateTransitionException(
                    "Contract %s is %s".formatted(contractId, contract.status()));
        }
        if (!deposit.getCurrency().getCurrencyCode().equals(contract.sourceCurrency())) {
            throw new com.wise.escrow.domain.exception.CurrencyMismatchException(
                    "Deposit must be in " + contract.sourceCurrency());
        }

        Account buyerAccount = accounts
                .findByEntityIdAndTypeAndCurrencyForUpdate(
                        actorEntityId, AccountType.USER, contract.sourceCurrency())
                .orElseThrow(() -> new NotFoundException("Funding account not found"));
        Account clearingSource = systemAccount(AccountType.WISE_CLEARING, contract.sourceCurrency());
        Account feeAccount = systemAccount(AccountType.WISE_FEE, contract.sourceCurrency());

        BigDecimal depositValue = deposit.getNumber().numberValue(BigDecimal.class);
        BigDecimal fee = depositValue.multiply(CONVERSION_FEE_RATE)
                .setScale(2, RoundingMode.HALF_EVEN).max(CENT);
        BigDecimal principal = depositValue.subtract(fee);
        BigDecimal rate = fx.getRate(contract.sourceCurrency(), contract.targetCurrency());
        BigDecimal converted = principal.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal target = contract.targetAmount().getNumber().numberValue(BigDecimal.class);
        if (converted.compareTo(target) < 0) {
            throw new InsufficientFundsException(
                    "Deposit %s converts to %s %s, below target %s"
                            .formatted(depositValue, converted, contract.targetCurrency(), target));
        }

        // Buyer must cover the whole deposit up front.
        if (buyerAccount.balance()
                        .subtract(deposit)
                        .isNegative()) {
            throw new InsufficientFundsException(
                    "Balance %s cannot cover deposit %s".formatted(buyerAccount.balance(), deposit));
        }

        UUID cid = contract.id();
        ledgerService.transfer(buyerAccount.id(), feeAccount.id(),
                MoneyAmounts.of(fee, contract.sourceCurrency()), "FX conversion fee (0.5%)", cid);
        ledgerService.transfer(buyerAccount.id(), clearingSource.id(),
                MoneyAmounts.of(principal, contract.sourceCurrency()), "Conversion principal", cid);

        Account vault = accounts.save(Account.open(null, AccountType.ESCROW_VAULT, contract.targetCurrency()));
        Account clearingTarget = systemAccount(AccountType.WISE_CLEARING, contract.targetCurrency());
        ledgerService.transfer(clearingTarget.id(), vault.id(),
                MoneyAmounts.of(converted, contract.targetCurrency()), "Escrow vault funding", cid);

        contract.fund(buyerAccount.id(), deposit, MoneyAmounts.of(converted, contract.targetCurrency()),
                rate, vault.id(), Instant.now());
        EscrowContract saved = contracts.save(contract);
        events.publishEvent(new ContractFunded(cid, contract.buyerId(), contract.sellerId()));
        return saved;
    }

    /**
     * Webhook settlement: vault drains 0.8% to the Wise fee account and
     * the rest to the seller's target-currency account. Idempotent — a
     * repeated call on a SETTLED contract returns it unchanged so webhook
     * redelivery never double-pays.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract settleMilestone(UUID contractId) {
        EscrowContract contract = contracts
                .findByIdForUpdate(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
        if (contract.status() == EscrowStatus.SETTLED) {
            return contract; // idempotent replay
        }
        if (contract.status() != EscrowStatus.FUNDED) {
            throw new InvalidStateTransitionException(
                    "Cannot settle contract %s in status %s".formatted(contractId, contract.status()));
        }

        Account vault = lockedAccount(contract.vaultAccountId());
        Account sellerAccount = accounts
                .findByEntityIdAndTypeAndCurrencyForUpdate(
                        contract.sellerId(), AccountType.USER, contract.targetCurrency())
                .orElseThrow(() -> new NotFoundException("Seller account not found"));
        Account feeAccount = systemAccount(AccountType.WISE_FEE, contract.targetCurrency());

        BigDecimal escrowed = contract.escrowedAmount().getNumber().numberValue(BigDecimal.class);
        BigDecimal fee = escrowed.multiply(SETTLEMENT_FEE_RATE).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal payout = escrowed.subtract(fee);

        UUID cid = contract.id();
        ledgerService.transfer(vault.id(), feeAccount.id(),
                MoneyAmounts.of(fee, contract.targetCurrency()), "Settlement fee (0.8%)", cid);
        ledgerService.transfer(vault.id(), sellerAccount.id(),
                MoneyAmounts.of(payout, contract.targetCurrency()), "Seller payout", cid);

        contract.settle();
        EscrowContract saved = contracts.save(contract);
        events.publishEvent(new ContractSettled(cid, contract.buyerId(), contract.sellerId()));
        return saved;
    }

    /**
     * Buyer-only asymmetric cancellation: before funding it's free; after
     * funding the vault liquidates back to source currency at the current
     * spot rate (buyer absorbs FX drift; Wise keeps the conversion fee).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract cancelContract(UUID contractId, UUID actorEntityId) {
        EscrowContract contract = contracts
                .findByIdForUpdate(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
        if (!contract.involves(actorEntityId)) {
            throw new NotFoundException("Contract not found: " + contractId);
        }
        if (!contract.isBuyer(actorEntityId)) {
            throw new ForbiddenException("Only the buyer may cancel this contract");
        }
        if (contract.status() == EscrowStatus.SETTLED) {
            throw new InvalidStateTransitionException("SETTLED contracts cannot be cancelled");
        }
        if (contract.status() == EscrowStatus.CANCELLED) {
            return contract;
        }
        if (contract.status() == EscrowStatus.FUNDED) {
            refund(contract);
        }
        contract.cancel();
        EscrowContract saved = contracts.save(contract);
        events.publishEvent(new ContractCancelled(contract.id(), contract.buyerId(), contract.sellerId()));
        return saved;
    }

    private void refund(EscrowContract contract) {
        Account vault = lockedAccount(contract.vaultAccountId());
        Account clearingTarget = systemAccount(AccountType.WISE_CLEARING, contract.targetCurrency());
        Account clearingSource = systemAccount(AccountType.WISE_CLEARING, contract.sourceCurrency());
        Account buyerAccount = lockedAccount(contract.buyerAccountId());

        BigDecimal escrowed = contract.escrowedAmount().getNumber().numberValue(BigDecimal.class);
        BigDecimal spot = fx.getRate(contract.targetCurrency(), contract.sourceCurrency());
        BigDecimal refund = escrowed.multiply(spot).setScale(2, RoundingMode.HALF_EVEN);

        UUID cid = contract.id();
        ledgerService.transfer(vault.id(), clearingTarget.id(),
                MoneyAmounts.of(escrowed, contract.targetCurrency()), "Vault liquidation", cid);
        ledgerService.transfer(clearingSource.id(), buyerAccount.id(),
                MoneyAmounts.of(refund, contract.sourceCurrency()), "Refund at current spot", cid);
    }

    // ---------- quote ----------

    /** Smallest source deposit whose net conversion still covers target. */
    public Quote quote(String sourceCurrency, String targetCurrency, BigDecimal targetAmount) {
        BigDecimal rate = fx.getRate(sourceCurrency, targetCurrency);
        BigDecimal oneMinusFee = BigDecimal.ONE.subtract(CONVERSION_FEE_RATE);
        BigDecimal total = targetAmount
                .divide(oneMinusFee.multiply(rate), 8, RoundingMode.HALF_EVEN)
                .setScale(2, RoundingMode.CEILING);
        // A quoted trade always carries a fee — floor at one cent so
        // sub-cent targets cannot round the fee down to zero.
        BigDecimal fee = total.multiply(CONVERSION_FEE_RATE).setScale(2, RoundingMode.HALF_EVEN).max(CENT);
        while (total.subtract(fee).multiply(rate).setScale(2, RoundingMode.HALF_EVEN)
                        .compareTo(targetAmount)
                < 0) {
            total = total.add(CENT);
            fee = total.multiply(CONVERSION_FEE_RATE).setScale(2, RoundingMode.HALF_EVEN).max(CENT);
        }
        return new Quote(sourceCurrency, targetCurrency, targetAmount, rate, total.subtract(fee), fee, total);
    }

    public record Quote(
            String sourceCurrency,
            String targetCurrency,
            BigDecimal targetAmount,
            BigDecimal midMarketRate,
            BigDecimal principal,
            BigDecimal wiseFee,
            BigDecimal totalDeposit) {

        /** Transparency block: legacy-bank spread 3.8% + $150 SWIFT fee. */
        public record BankEstimate(BigDecimal spreadCost, BigDecimal swiftFee, BigDecimal totalCost) {}

        public BankEstimate traditionalBankEstimate() {
            BigDecimal spread = totalDeposit.multiply(new BigDecimal("0.038"))
                    .setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal swift = new BigDecimal("150.00");
            return new BankEstimate(spread, swift, spread.add(swift));
        }
    }

    // ---------- helpers ----------

    private Account systemAccount(AccountType type, String currency) {
        return accounts
                .findSystemAccountForUpdate(type, currency)
                .orElseGet(() -> {
                    Account created = accounts.save(Account.open(null, type, currency));
                    return accounts
                            .findSystemAccountForUpdate(type, currency)
                            .orElse(created);
                });
    }

    private Account lockedAccount(UUID id) {
        return accounts.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Account not found: " + id));
    }
}
