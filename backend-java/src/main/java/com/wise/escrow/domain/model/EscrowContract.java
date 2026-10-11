package com.wise.escrow.domain.model;

import com.wise.escrow.domain.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import javax.money.MonetaryAmount;

/**
 * Conditional-settlement aggregate. Lifecycle:
 * INITIATED -> FUNDED -> SETTLED, or -> CANCELLED from INITIATED/FUNDED.
 * escrowedAmount is persisted at funding time — settlement pays out the
 * snapshot, never a recomputed conversion.
 */
public class EscrowContract {

    private final UUID id;
    private final UUID buyerId;
    private final UUID sellerId;
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
    /** Row version at load time — the adapter refuses to save staleness. */
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

    public void settle() {
        requireStatus(EscrowStatus.FUNDED, "settle");
        this.status = EscrowStatus.SETTLED;
    }

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

    public boolean involves(UUID entityId) {
        return buyerId.equals(entityId) || sellerId.equals(entityId);
    }

    public UUID id() {
        return id;
    }

    public UUID buyerId() {
        return buyerId;
    }

    public UUID sellerId() {
        return sellerId;
    }

    public UUID buyerAccountId() {
        return buyerAccountId;
    }

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

    public MonetaryAmount depositAmount() {
        return depositAmount;
    }

    public BigDecimal snapshotSpotRate() {
        return snapshotSpotRate;
    }

    public MonetaryAmount escrowedAmount() {
        return escrowedAmount;
    }

    public EscrowStatus status() {
        return status;
    }

    public TrackingType trackingType() {
        return trackingType;
    }

    public String trackingKey() {
        return trackingKey;
    }

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
