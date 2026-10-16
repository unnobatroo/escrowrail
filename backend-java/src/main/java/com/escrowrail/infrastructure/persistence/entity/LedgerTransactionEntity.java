package com.escrowrail.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One balanced double-entry leg: debit -> credit, single currency. */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID debitAccountId;

    @Column(nullable = false)
    private UUID creditAccountId;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String description;

    private UUID contractId;

    @Column(nullable = false)
    private Instant createdAt;

    /** SHA-256 chain link: hash(prev_hash, txn_id, debit, credit, amount, timestamp). */
    @Column(nullable = false, length = 64)
    private String currentHash;

    /** JPA / reflective use only. */
    protected LedgerTransactionEntity() {}
    public LedgerTransactionEntity(
            UUID id,
            UUID debitAccountId,
            UUID creditAccountId,
            BigDecimal amount,
            String currency,
            String description,
            UUID contractId,
            Instant createdAt,
            String currentHash) {
        this.id = id;
        this.debitAccountId = debitAccountId;
        this.creditAccountId = creditAccountId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.contractId = contractId;
        this.createdAt = createdAt;
        this.currentHash = currentHash;
    }

    public UUID getId() { return id; }
    public UUID getDebitAccountId() { return debitAccountId; }
    public UUID getCreditAccountId() { return creditAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public UUID getContractId() { return contractId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCurrentHash() { return currentHash; }
}
