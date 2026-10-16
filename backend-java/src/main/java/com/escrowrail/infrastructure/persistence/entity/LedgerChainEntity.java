package com.escrowrail.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Singleton holding the ledger hash-chain head; every insert locks it FOR UPDATE so chaining serializes. */
@Entity
@Table(name = "ledger_chain")
public class LedgerChainEntity {

    @Id
    private short id;

    @Column(nullable = false, length = 64)
    private String currentHash;

    /** JPA / reflective use only. */
    protected LedgerChainEntity() {}
    public LedgerChainEntity(short id, String currentHash) {
        this.id = id;
        this.currentHash = currentHash;
    }

    public short getId() { return id; }
    public String getCurrentHash() { return currentHash; }
    /** Advances the chain head. */
    public void setCurrentHash(String hash) { this.currentHash = hash; }
}
