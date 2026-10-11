package com.wise.escrow.infrastructure.persistence;

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

    /** For JPA / reflective use only. */
    protected LedgerTransactionEntity() {}

    /** Creates a new LedgerTransactionEntity. */
    public LedgerTransactionEntity(
            UUID id,
            UUID debitAccountId,
            UUID creditAccountId,
            BigDecimal amount,
            String currency,
            String description,
            UUID contractId,
            Instant createdAt) {
        this.id = id;
        this.debitAccountId = debitAccountId;
        this.creditAccountId = creditAccountId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.contractId = contractId;
        this.createdAt = createdAt;
    }

    /** Returns the id. */
    public UUID getId() { return id; }
    /** Returns the debit account id. */
    public UUID getDebitAccountId() { return debitAccountId; }
    /** Returns the credit account id. */
    public UUID getCreditAccountId() { return creditAccountId; }
    /** Returns the amount. */
    public BigDecimal getAmount() { return amount; }
    /** Returns the currency. */
    public String getCurrency() { return currency; }
    /** Returns the description. */
    public String getDescription() { return description; }
    /** Returns the contract id. */
    public UUID getContractId() { return contractId; }
    /** Returns the created at. */
    public Instant getCreatedAt() { return createdAt; }
}
