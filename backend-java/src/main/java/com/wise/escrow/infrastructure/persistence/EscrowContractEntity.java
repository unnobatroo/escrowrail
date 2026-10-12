package com.escrowrail.escrow.infrastructure.persistence;

import com.escrowrail.escrow.domain.model.EscrowStatus;
import com.escrowrail.escrow.domain.model.TrackingType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "escrow_contracts")
public class EscrowContractEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID buyerId;

    /** Null while the contract sits INVITED awaiting counterparty signup. */
    private UUID sellerId;

    /** Counterparty address an INVITED contract attaches to on registration. */
    @Column(length = 320)
    private String invitedEmail;

    private UUID buyerAccountId;
    private UUID vaultAccountId;

    @Column(nullable = false, length = 3)
    private String targetCurrency;

    @Column(nullable = false, length = 3)
    private String sourceCurrency;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal targetAmount;

    @Column(precision = 20, scale = 2)
    private BigDecimal depositAmount;

    @Column(precision = 20, scale = 8)
    private BigDecimal snapshotSpotRate;

    @Column(precision = 20, scale = 2)
    private BigDecimal escrowedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EscrowStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrackingType trackingType;

    @Column(nullable = false)
    private String trackingKey;

    private Instant fundedAt;

    @Column(nullable = false)
    private Instant createdAt;

    /** Optimistic concurrency guard on top of the pessimistic row locks. */
    @jakarta.persistence.Version
    private long version;

    /** For JPA / reflective use only. */
    protected EscrowContractEntity() {}

    /** Returns the version. */
    public long getVersion() {
        return version;
    }

    /** Returns the id. */
    public UUID getId() {
        return id;
    }

    /** Sets the id. */
    public void setId(UUID id) {
        this.id = id;
    }

    /** Returns the buyer id. */
    public UUID getBuyerId() {
        return buyerId;
    }

    /** Sets the buyer id. */
    public void setBuyerId(UUID buyerId) {
        this.buyerId = buyerId;
    }

    /** Returns the invited counterparty email. */
    public String getInvitedEmail() {
        return invitedEmail;
    }

    /** Sets the invited counterparty email. */
    public void setInvitedEmail(String invitedEmail) {
        this.invitedEmail = invitedEmail;
    }

    /** Returns the seller id. */
    public UUID getSellerId() {
        return sellerId;
    }

    /** Sets the seller id. */
    public void setSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    /** Returns the buyer account id. */
    public UUID getBuyerAccountId() {
        return buyerAccountId;
    }

    /** Sets the buyer account id. */
    public void setBuyerAccountId(UUID v) {
        this.buyerAccountId = v;
    }

    /** Returns the vault account id. */
    public UUID getVaultAccountId() {
        return vaultAccountId;
    }

    /** Sets the vault account id. */
    public void setVaultAccountId(UUID v) {
        this.vaultAccountId = v;
    }

    /** Returns the target currency. */
    public String getTargetCurrency() {
        return targetCurrency;
    }

    /** Sets the target currency. */
    public void setTargetCurrency(String v) {
        this.targetCurrency = v;
    }

    /** Returns the source currency. */
    public String getSourceCurrency() {
        return sourceCurrency;
    }

    /** Sets the source currency. */
    public void setSourceCurrency(String v) {
        this.sourceCurrency = v;
    }

    /** Returns the target amount. */
    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    /** Sets the target amount. */
    public void setTargetAmount(BigDecimal v) {
        this.targetAmount = v;
    }

    /** Returns the deposit amount. */
    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    /** Sets the deposit amount. */
    public void setDepositAmount(BigDecimal v) {
        this.depositAmount = v;
    }

    /** Returns the snapshot spot rate. */
    public BigDecimal getSnapshotSpotRate() {
        return snapshotSpotRate;
    }

    /** Sets the snapshot spot rate. */
    public void setSnapshotSpotRate(BigDecimal v) {
        this.snapshotSpotRate = v;
    }

    /** Returns the escrowed amount. */
    public BigDecimal getEscrowedAmount() {
        return escrowedAmount;
    }

    /** Sets the escrowed amount. */
    public void setEscrowedAmount(BigDecimal v) {
        this.escrowedAmount = v;
    }

    /** Returns the status. */
    public EscrowStatus getStatus() {
        return status;
    }

    /** Sets the status. */
    public void setStatus(EscrowStatus v) {
        this.status = v;
    }

    /** Returns the tracking type. */
    public TrackingType getTrackingType() {
        return trackingType;
    }

    /** Sets the tracking type. */
    public void setTrackingType(TrackingType v) {
        this.trackingType = v;
    }

    /** Returns the tracking key. */
    public String getTrackingKey() {
        return trackingKey;
    }

    /** Sets the tracking key. */
    public void setTrackingKey(String v) {
        this.trackingKey = v;
    }

    /** Returns the funded at. */
    public Instant getFundedAt() {
        return fundedAt;
    }

    /** Sets the funded at. */
    public void setFundedAt(Instant v) {
        this.fundedAt = v;
    }

    /** Returns the created at. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Sets the created at. */
    public void setCreatedAt(Instant v) {
        this.createdAt = v;
    }
}
