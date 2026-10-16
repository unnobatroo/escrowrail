package com.escrowrail.domain.model;

import com.escrowrail.domain.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import javax.money.MonetaryAmount;

/** Conditional settlement: INITIATED -> FUNDED -> SETTLED, or CANCELLED. escrowedAmount snapshots at funding; settlement pays the snapshot, never recomputes. */
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
    /** Optimistic-lock row version. */
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
        assertCentScale(targetAmount);
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

    private static void assertCentScale(MonetaryAmount amount) {
        if (amount != null) {
            BigDecimal dec = amount.getNumber().numberValue(BigDecimal.class);
            if (dec.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException(
                    "Amounts must have at most 2 decimal places"
                );
            }
        }
    }

    /** INITIATED contract between registered entities. */
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

    /** INVITED contract for an unregistered counterparty; attaches on signup. */
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

    /** Marks a seller-less contract INVITED (creation + rehydration). */
    public void markInvited(String email) {
        this.invitedEmail = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        this.status = EscrowStatus.INVITED;
    }

    /** INVITED -> INITIATED once the seller registers; fails unless INVITED. */
    public void attachSeller(UUID entityId) {
        requireStatus(EscrowStatus.INVITED, "attach seller to");
        this.sellerId = entityId;
        this.invitedEmail = null;
        this.status = EscrowStatus.INITIATED;
    }

    /** INITIATED -> FUNDED with the funding snapshot; callers own monetary validation and ledger transfers. */
    public void fund(
        UUID buyerAccountId,
        MonetaryAmount deposit,
        MonetaryAmount escrowed,
        BigDecimal spotRate,
        UUID vaultAccountId,
        Instant fundedAt
    ) {
        requireStatus(EscrowStatus.INITIATED, "fund");
        assertCentScale(deposit);
        assertCentScale(escrowed);
        this.buyerAccountId = buyerAccountId;
        this.depositAmount = deposit;
        this.escrowedAmount = escrowed;
        this.snapshotSpotRate = spotRate;
        this.vaultAccountId = vaultAccountId;
        this.fundedAt = fundedAt;
        this.status = EscrowStatus.FUNDED;
    }

    /** FUNDED -> SETTLED, retaining the funding snapshot. */
    public void settle() {
        requireStatus(EscrowStatus.FUNDED, "settle");
        this.status = EscrowStatus.SETTLED;
    }

    /** INITIATED/FUNDED -> CANCELLED, retaining any snapshot; callers own refunds. */
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

    public boolean isBuyer(UUID entityId) {
        return buyerId.equals(entityId);
    }

    /** True for buyer or seller. */
    public boolean involves(UUID entityId) {
        return buyerId.equals(entityId)
            || (sellerId != null && sellerId.equals(entityId));
    }

    public UUID id() {
        return id;
    }

    public UUID buyerId() {
        return buyerId;
    }

    /** Null while INVITED. */
    public UUID sellerId() {
        return sellerId;
    }

    /** Null once a seller attaches. */
    public String invitedEmail() {
        return invitedEmail;
    }

    /** Set at funding. */
    public UUID buyerAccountId() {
        return buyerAccountId;
    }

    /** Segregated vault created at funding. */
    public UUID vaultAccountId() {
        return vaultAccountId;
    }

    public String targetCurrency() {
        return targetCurrency;
    }

    public String sourceCurrency() {
        return sourceCurrency;
    }

    public MonetaryAmount targetAmount() {
        return targetAmount;
    }

    /** Source-currency deposit; set at funding. */
    public MonetaryAmount depositAmount() {
        return depositAmount;
    }

    /** Spot rate locked at funding. */
    public BigDecimal snapshotSpotRate() {
        return snapshotSpotRate;
    }

    /** Converted amount vaulted in target currency; set at funding. */
    public MonetaryAmount escrowedAmount() {
        return escrowedAmount;
    }

    public EscrowStatus status() {
        return status;
    }

    public TrackingType trackingType() {
        return trackingType;
    }

    /** PR URL or tracking number. */
    public String trackingKey() {
        return trackingKey;
    }

    /** Null until funded. */
    public Instant fundedAt() {
        return fundedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}
