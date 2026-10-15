package com.escrowrail.domain.model;

import com.escrowrail.domain.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import javax.money.MonetaryAmount;

/**
 * Conditional-settlement aggregate. Lifecycle: INITIATED -> FUNDED ->
 * SETTLED, or -> CANCELLED from INITIATED/FUNDED. escrowedAmount snapshots
 * at funding — settlement pays the snapshot, never a recomputation.
 */
public class EscrowContract {

    private final UUID id;
    private final UUID buyerId;
    private UUID sellerId;
    private String invitedEmail;
    private final String targetCurrency;
    private final String sourceCurrency;
    private final MonetaryAmount targetAmount;
    private final TrackingType trackingType;
    private final String trackingKey;
    private final Instant createdAt;

    private UUID buyerAccountId;
    private UUID vaultAccountId;
    private MonetaryAmount depositAmount;
    private BigDecimal snapshotSpotRate;
    private MonetaryAmount escrowedAmount;
    private EscrowStatus status;
    private Instant fundedAt;
    /** Loaded row version — the adapter refuses to save staleness. */
    private long version;

    public EscrowContract(
        UUID id,
        UUID buyerId,
        UUID sellerId,
        String targetCurrency,
        String sourceCurrency,
        MonetaryAmount targetAmount,
        TrackingType trackingType,
        String trackingKey,
        Instant createdAt
    ) {
        this(
            id, buyerId, sellerId, targetCurrency, sourceCurrency,
            targetAmount, trackingType, trackingKey, createdAt, 0L
        );
    }

    public EscrowContract(
        UUID id,
        UUID buyerId,
        UUID sellerId,
        String targetCurrency,
        String sourceCurrency,
        MonetaryAmount targetAmount,
        TrackingType trackingType,
        String trackingKey,
        Instant createdAt,
        long version
    ) {
        this.id = id;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.targetCurrency = targetCurrency;
        this.sourceCurrency = sourceCurrency;
        this.targetAmount = targetAmount;
        this.trackingType = trackingType;
        this.trackingKey = trackingKey;
        this.createdAt = createdAt;
        this.version = version;
        this.status = EscrowStatus.INITIATED;
    }

    /** INITIATED contract between two registered entities. */
    public static EscrowContract create(
        UUID buyerId,
        UUID sellerId,
        MonetaryAmount targetAmount,
        String sourceCurrency,
        TrackingType trackingType,
        String trackingKey
    ) {
        return new EscrowContract(
            UUID.randomUUID(),
            buyerId,
            sellerId,
            targetAmount.getCurrency().getCurrencyCode(),
            sourceCurrency,
            targetAmount,
            trackingType,
            trackingKey,
            Instant.now()
        );
    }

    /** INVITED contract for an unregistered counterparty; a later signup attaches. */
    public static EscrowContract createInvited(
        UUID buyerId,
        MonetaryAmount targetAmount,
        String sourceCurrency,
        TrackingType trackingType,
        String trackingKey,
        String invitedEmail
    ) {
        EscrowContract c = new EscrowContract(
            UUID.randomUUID(),
            buyerId,
            null,
            targetAmount.getCurrency().getCurrencyCode(),
            sourceCurrency,
            targetAmount,
            trackingType,
            trackingKey,
            Instant.now()
        );
        c.markInvited(invitedEmail);
        return c;
    }

    /** Marks a seller-less contract INVITED; used by creation and rehydration. */
    public void markInvited(String email) {
        this.invitedEmail = email;
        this.status = EscrowStatus.INVITED;
    }

    /**
     * Attaches the registered seller: INVITED -> INITIATED.
     *
     * @throws InvalidStateTransitionException if the contract is not INVITED
     */
    public void attachSeller(UUID entityId) {
        requireStatus(EscrowStatus.INVITED, "attach seller to");
        this.sellerId = entityId;
        this.invitedEmail = null;
        this.status = EscrowStatus.INITIATED;
    }

    /**
     * INITIATED -> FUNDED with the supplied funding snapshot. Callers own
     * monetary validation and ledger transfers.
     *
     * @param deposit the original deposit in source currency, including the conversion fee
     * @param escrowed the converted amount held in target currency
     * @param spotRate target-currency units per source-currency unit at funding
     * @throws InvalidStateTransitionException if the contract is not INITIATED
     */
    public void fund(
        UUID buyerAccountId,
        MonetaryAmount deposit,
        MonetaryAmount escrowed,
        BigDecimal spotRate,
        UUID vaultAccountId,
        Instant fundedAt
    ) {
        requireStatus(EscrowStatus.INITIATED, "fund");
        this.buyerAccountId = buyerAccountId;
        this.depositAmount = deposit;
        this.escrowedAmount = escrowed;
        this.snapshotSpotRate = spotRate;
        this.vaultAccountId = vaultAccountId;
        this.fundedAt = fundedAt;
        this.status = EscrowStatus.FUNDED;
    }

    /**
     * FUNDED -> SETTLED, retaining the funding snapshot.
     *
     * @throws InvalidStateTransitionException if the contract is not FUNDED,
     *     including when it is already SETTLED
     */
    public void settle() {
        requireStatus(EscrowStatus.FUNDED, "settle");
        this.status = EscrowStatus.SETTLED;
    }

    /**
     * INITIATED/FUNDED -> CANCELLED, retaining any snapshot. Callers own refunds.
     *
     * @throws InvalidStateTransitionException if already SETTLED or CANCELLED
     */
    public void cancel() {
        if (status == EscrowStatus.SETTLED) {
            throw new InvalidStateTransitionException(
                "Contract %s is SETTLED and cannot be cancelled".formatted(id)
            );
        }
        if (status == EscrowStatus.CANCELLED) {
            throw new InvalidStateTransitionException(
                "Contract %s is already CANCELLED".formatted(id)
            );
        }
        this.status = EscrowStatus.CANCELLED;
    }

    private void requireStatus(EscrowStatus expected, String op) {
        if (status != expected) {
            throw new InvalidStateTransitionException(
                "Cannot %s contract %s in status %s".formatted(op, id, status)
            );
        }
    }

    /** Whether the entity is the contract's buyer. */
    public boolean isBuyer(UUID entityId) {
        return buyerId.equals(entityId);
    }

    /** Whether the entity is buyer or seller on this contract. */
    public boolean involves(UUID entityId) {
        return buyerId.equals(entityId)
            || (sellerId != null && sellerId.equals(entityId));
    }

    /** Returns the contract id. */
    public UUID id() {
        return id;
    }

    /** Returns the buyer entity id. */
    public UUID buyerId() {
        return buyerId;
    }

    /** Returns the seller entity id, or null while INVITED. */
    public UUID sellerId() {
        return sellerId;
    }

    /** Invited counterparty email, while the seller slot is still open. */
    public String invitedEmail() {
        return invitedEmail;
    }

    /** Returns the funding account recorded at deposit time. */
    public UUID buyerAccountId() {
        return buyerAccountId;
    }

    /** Returns the segregated vault account created at funding. */
    public UUID vaultAccountId() {
        return vaultAccountId;
    }

    /** Returns the invoice (seller payout) currency. */
    public String targetCurrency() {
        return targetCurrency;
    }

    /** Returns the buyer deposit currency. */
    public String sourceCurrency() {
        return sourceCurrency;
    }

    /** Returns the target amount in invoice currency. */
    public MonetaryAmount targetAmount() {
        return targetAmount;
    }

    /** Returns the source-currency deposit recorded at funding. */
    public MonetaryAmount depositAmount() {
        return depositAmount;
    }

    /** Returns the spot rate locked at funding. */
    public BigDecimal snapshotSpotRate() {
        return snapshotSpotRate;
    }

    /** Returns the converted amount vaulted in target currency. */
    public MonetaryAmount escrowedAmount() {
        return escrowedAmount;
    }

    /** Returns the lifecycle status. */
    public EscrowStatus status() {
        return status;
    }

    /** Returns the milestone proof type (GitHub PR or carrier). */
    public TrackingType trackingType() {
        return trackingType;
    }

    /** Returns the milestone proof key (PR URL or tracking number). */
    public String trackingKey() {
        return trackingKey;
    }

    /** Returns the funding timestamp, or null before funding. */
    public Instant fundedAt() {
        return fundedAt;
    }

    /** Returns the creation timestamp. */
    public Instant createdAt() {
        return createdAt;
    }

    /** Returns the JPA optimistic-lock version. */
    public long version() {
        return version;
    }
}
