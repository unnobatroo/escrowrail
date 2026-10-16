package com.escrowrail.infrastructure.persistence.entity;

import com.escrowrail.domain.model.EscrowStatus;
import com.escrowrail.domain.model.TrackingType;
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

    /** JPA / reflective use only. */
    public EscrowContractEntity() {}

    public long getVersion() {
        return version;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(UUID buyerId) {
        this.buyerId = buyerId;
    }

    public String getInvitedEmail() {
        return invitedEmail;
    }

    public void setInvitedEmail(String invitedEmail) {
        this.invitedEmail = invitedEmail;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public void setSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    public UUID getBuyerAccountId() {
        return buyerAccountId;
    }

    public void setBuyerAccountId(UUID v) {
        this.buyerAccountId = v;
    }

    public UUID getVaultAccountId() {
        return vaultAccountId;
    }

    public void setVaultAccountId(UUID v) {
        this.vaultAccountId = v;
    }

    public String getTargetCurrency() {
        return targetCurrency;
    }

    public void setTargetCurrency(String v) {
        this.targetCurrency = v;
    }

    public String getSourceCurrency() {
        return sourceCurrency;
    }

    public void setSourceCurrency(String v) {
        this.sourceCurrency = v;
    }

    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(BigDecimal v) {
        this.targetAmount = v;
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public void setDepositAmount(BigDecimal v) {
        this.depositAmount = v;
    }

    public BigDecimal getSnapshotSpotRate() {
        return snapshotSpotRate;
    }

    public void setSnapshotSpotRate(BigDecimal v) {
        this.snapshotSpotRate = v;
    }

    public BigDecimal getEscrowedAmount() {
        return escrowedAmount;
    }

    public void setEscrowedAmount(BigDecimal v) {
        this.escrowedAmount = v;
    }

    public EscrowStatus getStatus() {
        return status;
    }

    public void setStatus(EscrowStatus v) {
        this.status = v;
    }

    public TrackingType getTrackingType() {
        return trackingType;
    }

    public void setTrackingType(TrackingType v) {
        this.trackingType = v;
    }

    public String getTrackingKey() {
        return trackingKey;
    }

    public void setTrackingKey(String v) {
        this.trackingKey = v;
    }

    public Instant getFundedAt() {
        return fundedAt;
    }

    public void setFundedAt(Instant v) {
        this.fundedAt = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant v) {
        this.createdAt = v;
    }
}
