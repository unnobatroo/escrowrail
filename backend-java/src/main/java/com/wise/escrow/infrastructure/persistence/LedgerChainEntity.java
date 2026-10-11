package com.escrowrail.escrow.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Singleton row holding the head of the ledger hash chain. Every
 * {@code ledger_transactions} insert locks this row FOR UPDATE so hash
 * chaining serializes correctly under concurrent transfers.
 */
@Entity
@Table(name = "ledger_chain")
public class LedgerChainEntity {

    @Id
    private short id;

    @Column(nullable = false, length = 64)
    private String currentHash;

    /** For JPA / reflective use only. */
    protected LedgerChainEntity() {}

    /** Creates a new LedgerChainEntity. */
    public LedgerChainEntity(short id, String currentHash) {
        this.id = id;
        this.currentHash = currentHash;
    }

    /** Returns the id. */
    public short getId() { return id; }
    /** Returns the current chain head hash. */
    public String getCurrentHash() { return currentHash; }
    /** Advances the chain head. */
    public void setCurrentHash(String hash) { this.currentHash = hash; }
}
