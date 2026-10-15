package com.escrowrail.application.service;

import com.escrowrail.application.dto.Events.ContractCancelled;
import com.escrowrail.application.dto.Events.ContractFunded;
import com.escrowrail.application.dto.Events.ContractSettled;
import com.escrowrail.application.dto.Events.CounterpartyInvited;
import com.escrowrail.domain.exception.CircularOwnershipException;
import com.escrowrail.domain.exception.ForbiddenException;
import com.escrowrail.domain.exception.InsufficientFundsException;
import com.escrowrail.domain.exception.InvalidStateTransitionException;
import com.escrowrail.domain.exception.NotFoundException;
import com.escrowrail.domain.exception.ValidationException;
import com.escrowrail.domain.model.Account;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.model.EscrowContract;
import com.escrowrail.domain.model.EscrowStatus;
import com.escrowrail.domain.model.MoneyAmounts;
import com.escrowrail.domain.model.Organization;
import com.escrowrail.domain.model.TrackingType;
import com.escrowrail.domain.repository.AccountRepository;
import com.escrowrail.domain.repository.AppUserRepository;
import com.escrowrail.domain.repository.EscrowContractRepository;
import com.escrowrail.domain.repository.LedgerRepository;
import com.escrowrail.domain.repository.LedgerRepository.LedgerEntry;
import com.escrowrail.domain.repository.OrganizationRepository;
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
 * Escrow lifecycle. Every mutation runs in one transaction holding
 * PESSIMISTIC_WRITE locks on the contract and all touched account rows;
 * events go through the Modulith outbox in the same transaction.
 */
@Service
public class EscrowService {

    /** Platform conversion fee, taken in source currency at funding time. */
    public static final BigDecimal CONVERSION_FEE_RATE = new BigDecimal(
        "0.005"
    );

    /** Settlement take rate, taken in target currency from the vault. */
    public static final BigDecimal SETTLEMENT_FEE_RATE = new BigDecimal(
        "0.008"
    );

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
        ApplicationEventPublisher events
    ) {
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

    /**
     * Contract visible to its buyer or seller organization.
     *
     * @throws NotFoundException if absent or the entity is not a party
     */
    @Transactional(readOnly = true)
    public EscrowContract getForEntity(UUID contractId, UUID entityId) {
        return contracts
            .findByIdForEntity(contractId, entityId)
            .orElseThrow(() ->
                new NotFoundException("Contract not found: " + contractId)
            );
    }

    @Transactional(readOnly = true)
    /** Contracts where the entity is buyer or seller. */
    public List<EscrowContract> listForEntity(UUID entityId) {
        return contracts.findAllForEntity(entityId);
    }

    /**
     * Ledger entries in creation order, caller must be a party.
     *
     * @throws NotFoundException if absent or the entity is not a party
     */
    @Transactional(readOnly = true)
    public List<LedgerEntry> auditForEntity(UUID contractId, UUID entityId) {
        getForEntity(contractId, entityId); // tenancy gate before the trail
        return ledger.findByContractIdOrderByCreatedAt(contractId);
    }

    /** Looks up a counterparty by normalized email. */
    @Transactional(readOnly = true)
    public Optional<Counterparty> resolveCounterparty(
        String email,
        UUID requesterEntityId
    ) {
        return users
            .findByEmail(email.strip().toLowerCase(java.util.Locale.ROOT))
            .flatMap(u -> organizations.findById(u.entityId()))
            .map(org ->
                new Counterparty(
                    org,
                    organizations
                        .findById(requesterEntityId)
                        .map(me -> me.uboTaxId().equals(org.uboTaxId()))
                        .orElse(false)
                )
            );
    }

    public record Counterparty(Organization organization, boolean sameUbo) {}

    /**
     * Counterparty invite via the outbox. Must run inside a transaction —
     * AFTER_COMMIT listeners miss events published without one.
     *
     * @throws NotFoundException if the inviter organization is absent
     */
    @Transactional
    public void inviteCounterparty(String email, UUID inviterEntityId) {
        Organization inviter = organizations
            .findById(inviterEntityId)
            .orElseThrow(() -> new NotFoundException("Organization not found"));
        events.publishEvent(
            new CounterpartyInvited(email, inviter.legalName())
        );
    }

    // ---------- lifecycle ----------

    /**
     * Creates an INITIATED contract after org, UBO, and FX checks. Target
     * amount is in the invoice currency.
     *
     * @throws NotFoundException if either organization is absent
     * @throws CircularOwnershipException if both organizations share a normalized UBO tax ID
     * @throws com.escrowrail.domain.exception.UnsupportedCurrencyPairException if the FX provider cannot quote the pair
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract createContract(
        UUID buyerEntityId,
        UUID sellerEntityId,
        MonetaryAmount targetAmount,
        String sourceCurrency,
        TrackingType trackingType,
        String trackingKey
    ) {
        Organization buyer = organizations
            .findById(buyerEntityId)
            .orElseThrow(() ->
                new NotFoundException("Buyer organization not found")
            );
        Organization seller = organizations
            .findById(sellerEntityId)
            .orElseThrow(() ->
                new NotFoundException("Seller organization not found")
            );
        if (buyer.uboTaxId().equals(seller.uboTaxId())) {
            throw new CircularOwnershipException(
                "Buyer and seller share UBO tax id " + buyer.uboTaxId().value()
            );
        }
        // Reject pairs the FX provider cannot quote — the contract would
        // never be fundable.
        fx.getRate(sourceCurrency, targetAmount.getCurrency().getCurrencyCode());
        return contracts.save(
            EscrowContract.create(
                buyerEntityId,
                sellerEntityId,
                targetAmount,
                sourceCurrency,
                trackingType,
                trackingKey
            )
        );
    }

    /**
     * INVITED contract for an unregistered counterparty; a later signup under
     * {@code invitedEmail} fills the seller slot via {@link #attachPendingInvites}.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract createInvitedContract(
        UUID buyerEntityId,
        MonetaryAmount targetAmount,
        String sourceCurrency,
        TrackingType trackingType,
        String trackingKey,
        String invitedEmail
    ) {
        fx.getRate(sourceCurrency, targetAmount.getCurrency().getCurrencyCode());
        return contracts.save(
            EscrowContract.createInvited(
                buyerEntityId,
                targetAmount,
                sourceCurrency,
                trackingType,
                trackingKey,
                invitedEmail
            )
        );
    }

    /**
     * Attaches INVITED contracts for {@code email} to the new entity — called
     * inside the registration transaction so pending trades arrive with the account.
     */
    @Transactional
    public void attachPendingInvites(String email, UUID sellerEntityId) {
        for (EscrowContract c : contracts.findPendingInvites(email)) {
            c.attachSeller(sellerEntityId);
            contracts.save(c);
        }
    }

    /**
     * Funds the contract: 0.5% fee in source currency, remainder converted at
     * spot and vaulted in target currency. Fee minimum 0.01; HALF_EVEN cents.
     * Publishes the funded event atomically.
     *
     * @return the saved funded contract
     * @throws NotFoundException if the contract/account is absent or actor is not a party
     * @throws ForbiddenException if the actor is the seller
     * @throws InvalidStateTransitionException if the contract is not INITIATED
     * @throws com.escrowrail.domain.exception.CurrencyMismatchException if the deposit is not in source currency
     * @throws InsufficientFundsException if the deposit does not cover the target
     *     after conversion or the buyer cannot cover the deposit
     * @throws IllegalStateException if a required system account is missing
     * @throws com.escrowrail.domain.exception.UnsupportedCurrencyPairException if no FX rate is available
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract fundContract(
        UUID contractId,
        UUID actorEntityId,
        MonetaryAmount deposit
    ) {
        EscrowContract contract = contracts
            .findByIdForUpdate(contractId)
            .orElseThrow(() ->
                new NotFoundException("Contract not found: " + contractId)
            );
        if (!contract.involves(actorEntityId)) {
            throw new NotFoundException("Contract not found: " + contractId);
        }
        if (!contract.isBuyer(actorEntityId)) {
            throw new ForbiddenException(
                "Only the buyer may fund this contract"
            );
        }
        if (contract.status() != EscrowStatus.INITIATED) {
            throw new InvalidStateTransitionException(
                "Contract %s is %s".formatted(contractId, contract.status())
            );
        }
        if (
            !deposit
                .getCurrency()
                .getCurrencyCode()
                .equals(contract.sourceCurrency())
        ) {
            throw new com.escrowrail.domain.exception.CurrencyMismatchException(
                "Deposit must be in " + contract.sourceCurrency()
            );
        }

        UUID buyerAccountId = accounts
            .findAccountId(
                actorEntityId,
                AccountType.USER,
                contract.sourceCurrency()
            )
            .orElseThrow(() ->
                new NotFoundException("Funding account not found")
            );
        UUID clearingSourceId = systemAccountId(
            AccountType.ESCROWRAIL_CLEARING,
            contract.sourceCurrency()
        );
        UUID clearingTargetId = systemAccountId(
            AccountType.ESCROWRAIL_CLEARING,
            contract.targetCurrency()
        );
        UUID feeAccountId = systemAccountId(
            AccountType.ESCROWRAIL_FEE,
            contract.sourceCurrency()
        );
        // Lock all touched rows in global UUID order (as LedgerService) —
        // fund vs refund would deadlock on opposite clearing accounts.
        var locked = lockAll(
            List.of(buyerAccountId, clearingSourceId, clearingTargetId, feeAccountId)
        );
        Account buyerAccount = locked.get(buyerAccountId);
        Account clearingSource = locked.get(clearingSourceId);
        Account feeAccount = locked.get(feeAccountId);

        BigDecimal depositValue = deposit
            .getNumber()
            .numberValue(BigDecimal.class);
        if (depositValue.stripTrailingZeros().scale() > 2) {
            throw new ValidationException(
                "depositAmount must have at most 2 decimal places"
            );
        }
        BigDecimal fee = depositValue
            .multiply(CONVERSION_FEE_RATE)
            .setScale(2, RoundingMode.HALF_EVEN)
            .max(CENT);
        BigDecimal principal = depositValue.subtract(fee);
        BigDecimal rate = fx.getRate(
            contract.sourceCurrency(),
            contract.targetCurrency()
        );
        BigDecimal converted = principal
            .multiply(rate)
            .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal target = contract
            .targetAmount()
            .getNumber()
            .numberValue(BigDecimal.class);
        if (converted.compareTo(target) < 0) {
            throw new InsufficientFundsException(
                "Deposit %s converts to %s %s, below target %s".formatted(
                    depositValue,
                    converted,
                    contract.targetCurrency(),
                    target
                )
            );
        }
        // Over-deposit would over-vault the target — reject rather than escrow.
        Quote required = quote(
            contract.sourceCurrency(),
            contract.targetCurrency(),
            target
        );
        if (depositValue.compareTo(required.totalDeposit()) > 0) {
            throw new ValidationException(
                "Deposit %s exceeds quoted total %s".formatted(
                    depositValue,
                    required.totalDeposit()
                )
            );
        }

        // Full deposit up front; empty account gets its own code so the UI
        // can route to top-up.
        BigDecimal buyerBalance = buyerAccount
            .balance()
            .getNumber()
            .numberValue(BigDecimal.class);
        if (buyerBalance.signum() == 0) {
            throw new com.escrowrail.domain.exception.ZeroBalanceException(
                "Your %s account is empty — it needs %s to fund this trade"
                    .formatted(contract.sourceCurrency(), depositValue)
            );
        }
        if (buyerAccount.balance().subtract(deposit).isNegative()) {
            throw new InsufficientFundsException(
                "Balance %s cannot cover deposit %s".formatted(
                    buyerAccount.balance(),
                    deposit
                )
            );
        }

        UUID cid = contract.id();
        ledgerService.transfer(
            buyerAccount.id(),
            feeAccount.id(),
            MoneyAmounts.of(fee, contract.sourceCurrency()),
            "FX conversion fee (0.5%)",
            cid
        );
        ledgerService.transfer(
            buyerAccount.id(),
            clearingSource.id(),
            MoneyAmounts.of(principal, contract.sourceCurrency()),
            "Conversion principal",
            cid
        );

        Account vault = accounts.save(
            Account.open(
                null,
                AccountType.ESCROW_VAULT,
                contract.targetCurrency()
            )
        );
        ledgerService.transfer(
            clearingTargetId,
            vault.id(),
            MoneyAmounts.of(converted, contract.targetCurrency()),
            "Escrow vault funding",
            cid
        );

        contract.fund(
            buyerAccount.id(),
            deposit,
            MoneyAmounts.of(converted, contract.targetCurrency()),
            rate,
            vault.id(),
            Instant.now()
        );
        EscrowContract saved = contracts.save(contract);
        events.publishEvent(
            new ContractFunded(cid, contract.buyerId(), contract.sellerId())
        );
        return saved;
    }

    /**
     * Webhook settlement: 0.8% to the fee account, remainder to the seller.
     * Idempotent — a repeat call on SETTLED returns it unchanged, so webhook
     * redelivery never double-pays.
     *
     * @return the saved or previously settled contract
     * @throws NotFoundException if the contract or a required user/vault account is absent
     * @throws InvalidStateTransitionException if neither FUNDED nor SETTLED
     * @throws IllegalStateException if the system fee account is missing
     * @throws InsufficientFundsException if the vault cannot cover the transfers
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract settleMilestone(UUID contractId) {
        EscrowContract contract = contracts
            .findByIdForUpdate(contractId)
            .orElseThrow(() ->
                new NotFoundException("Contract not found: " + contractId)
            );
        if (contract.status() == EscrowStatus.SETTLED) {
            return contract; // idempotent replay
        }
        if (contract.status() != EscrowStatus.FUNDED) {
            throw new InvalidStateTransitionException(
                "Cannot settle contract %s in status %s".formatted(
                    contractId,
                    contract.status()
                )
            );
        }

        UUID sellerAccountId = accounts
            .findAccountId(
                contract.sellerId(),
                AccountType.USER,
                contract.targetCurrency()
            )
            .orElseThrow(() ->
                new NotFoundException("Seller account not found")
            );
        UUID feeAccountId = systemAccountId(
            AccountType.ESCROWRAIL_FEE,
            contract.targetCurrency()
        );
        var locked = lockAll(
            List.of(contract.vaultAccountId(), sellerAccountId, feeAccountId)
        );
        Account vault = locked.get(contract.vaultAccountId());
        Account sellerAccount = locked.get(sellerAccountId);
        Account feeAccount = locked.get(feeAccountId);

        BigDecimal escrowed = contract
            .escrowedAmount()
            .getNumber()
            .numberValue(BigDecimal.class);
        BigDecimal fee = escrowed
            .multiply(SETTLEMENT_FEE_RATE)
            .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal payout = escrowed.subtract(fee);

        UUID cid = contract.id();
        ledgerService.transfer(
            vault.id(),
            feeAccount.id(),
            MoneyAmounts.of(fee, contract.targetCurrency()),
            "Settlement fee (0.8%)",
            cid
        );
        ledgerService.transfer(
            vault.id(),
            sellerAccount.id(),
            MoneyAmounts.of(payout, contract.targetCurrency()),
            "Seller payout",
            cid
        );

        contract.settle();
        EscrowContract saved = contracts.save(contract);
        events.publishEvent(
            new ContractSettled(cid, contract.buyerId(), contract.sellerId())
        );
        return saved;
    }

    /**
     * Buyer-only cancellation: free before funding; after funding the vault
     * liquidates to source currency at current spot (buyer absorbs FX drift,
     * the conversion fee is kept). Idempotent on CANCELLED.
     *
     * @return the saved or previously cancelled contract
     * @throws NotFoundException if the contract or a required refund account is absent,
     *     or the actor is not a party
     * @throws ForbiddenException if the actor is the seller
     * @throws InvalidStateTransitionException if the contract is SETTLED
     * @throws IllegalStateException if a required system account is missing
     * @throws InsufficientFundsException if the vault cannot cover liquidation
     * @throws com.escrowrail.domain.exception.UnsupportedCurrencyPairException if no refund FX rate is available
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract cancelContract(UUID contractId, UUID actorEntityId) {
        EscrowContract contract = contracts
            .findByIdForUpdate(contractId)
            .orElseThrow(() ->
                new NotFoundException("Contract not found: " + contractId)
            );
        if (!contract.involves(actorEntityId)) {
            throw new NotFoundException("Contract not found: " + contractId);
        }
        if (!contract.isBuyer(actorEntityId)) {
            throw new ForbiddenException(
                "Only the buyer may cancel this contract"
            );
        }
        if (contract.status() == EscrowStatus.SETTLED) {
            throw new InvalidStateTransitionException(
                "SETTLED contracts cannot be cancelled"
            );
        }
        if (contract.status() == EscrowStatus.CANCELLED) {
            return contract;
        }
        if (contract.status() == EscrowStatus.FUNDED) {
            refund(contract);
        }
        contract.cancel();
        EscrowContract saved = contracts.save(contract);
        events.publishEvent(
            new ContractCancelled(
                contract.id(),
                contract.buyerId(),
                contract.sellerId()
            )
        );
        return saved;
    }

    private void refund(EscrowContract contract) {
        UUID clearingTargetId = systemAccountId(
            AccountType.ESCROWRAIL_CLEARING,
            contract.targetCurrency()
        );
        UUID clearingSourceId = systemAccountId(
            AccountType.ESCROWRAIL_CLEARING,
            contract.sourceCurrency()
        );
        var locked = lockAll(
            List.of(
                contract.vaultAccountId(),
                contract.buyerAccountId(),
                clearingTargetId,
                clearingSourceId
            )
        );
        Account vault = locked.get(contract.vaultAccountId());
        Account clearingTarget = locked.get(clearingTargetId);
        Account clearingSource = locked.get(clearingSourceId);
        Account buyerAccount = locked.get(contract.buyerAccountId());

        BigDecimal escrowed = contract
            .escrowedAmount()
            .getNumber()
            .numberValue(BigDecimal.class);
        BigDecimal spot = fx.getRate(
            contract.targetCurrency(),
            contract.sourceCurrency()
        );
        BigDecimal refund = escrowed
            .multiply(spot)
            .setScale(2, RoundingMode.HALF_EVEN);

        UUID cid = contract.id();
        ledgerService.transfer(
            vault.id(),
            clearingTarget.id(),
            MoneyAmounts.of(escrowed, contract.targetCurrency()),
            "Vault liquidation",
            cid
        );
        ledgerService.transfer(
            clearingSource.id(),
            buyerAccount.id(),
            MoneyAmounts.of(refund, contract.sourceCurrency()),
            "Refund at current spot",
            cid
        );
    }

    // ---------- quote ----------

    /**
     * Quotes the cent-rounded source deposit whose net conversion covers the
     * target. Fee: 0.5%, HALF_EVEN, 0.01 minimum. The rate is not reserved.
     *
     * @return target amount, rate, and principal/fee/total in source currency
     * @throws com.escrowrail.domain.exception.ValidationException if the target is null or nonpositive
     * @throws com.escrowrail.domain.exception.UnsupportedCurrencyPairException if no FX rate is available
     */
    public Quote quote(
        String sourceCurrency,
        String targetCurrency,
        BigDecimal targetAmount
    ) {
        if (targetAmount == null || targetAmount.signum() <= 0) {
            throw new ValidationException("targetAmount must be positive");
        }
        BigDecimal rate = fx.getRate(sourceCurrency, targetCurrency);
        BigDecimal oneMinusFee = BigDecimal.ONE.subtract(CONVERSION_FEE_RATE);
        BigDecimal total = targetAmount
            .divide(oneMinusFee.multiply(rate), 8, RoundingMode.HALF_EVEN)
            .setScale(2, RoundingMode.CEILING);
        // Fee floors at one cent so sub-cent targets cannot round to zero.
        BigDecimal fee = total
            .multiply(CONVERSION_FEE_RATE)
            .setScale(2, RoundingMode.HALF_EVEN)
            .max(CENT);
        while (
            total
                .subtract(fee)
                .multiply(rate)
                .setScale(2, RoundingMode.HALF_EVEN)
                .compareTo(targetAmount) < 0
        ) {
            total = total.add(CENT);
            fee = total
                .multiply(CONVERSION_FEE_RATE)
                .setScale(2, RoundingMode.HALF_EVEN)
                .max(CENT);
        }
        return new Quote(
            sourceCurrency,
            targetCurrency,
            targetAmount,
            rate,
            total.subtract(fee),
            fee,
            total
        );
    }

    public record Quote(
        String sourceCurrency,
        String targetCurrency,
        BigDecimal targetAmount,
        BigDecimal midMarketRate,
        BigDecimal principal,
        BigDecimal platformFee,
        BigDecimal totalDeposit
    ) {
        /** Legacy-bank comparison: 3.8% spread + $150 SWIFT. */
        public record BankEstimate(
            BigDecimal spreadCost,
            BigDecimal swiftFee,
            BigDecimal totalCost
        ) {}

        /** 3.8% of deposit + fixed 150.00, HALF_EVEN cents, no FX conversion. */
        public BankEstimate traditionalBankEstimate() {
            BigDecimal spread = totalDeposit
                .multiply(new BigDecimal("0.038"))
                .setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal swift = new BigDecimal("150.00");
            return new BankEstimate(spread, swift, spread.add(swift));
        }
    }

    // ---------- helpers ----------

    /**
     * System account id, unlocked — callers lock via lockAll in global order.
     *
     * @throws IllegalStateException if the system account is missing
     */
    private UUID systemAccountId(AccountType type, String currency) {
        // System accounts are seeded by V1; creating one here would race.
        return accounts
            .findSystemAccountId(type, currency)
            .orElseThrow(() ->
                new IllegalStateException(
                    "System account missing: " + type + "/" + currency
                )
            );
    }

    private java.util.Map<UUID, Account> lockAll(List<UUID> ids) {
        return ledgerService.lockInOrder(ids);
    }
}
