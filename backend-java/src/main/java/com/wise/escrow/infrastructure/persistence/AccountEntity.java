package com.wise.escrow.infrastructure.persistence;

import com.wise.escrow.domain.model.AccountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    private UUID id;

    /** null = Wise system account (fee/clearing). */
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal balance;

    protected AccountEntity() {}

    public AccountEntity(UUID id, UUID entityId, AccountType type, String currency, BigDecimal balance) {
        this.id = id;
        this.entityId = entityId;
        this.type = type;
        this.currency = currency;
        this.balance = balance;
    }

    public UUID getId() { return id; }
    public UUID getEntityId() { return entityId; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
}
