package com.wise.escrow.domain.model;

import com.wise.escrow.domain.exception.CurrencyMismatchException;
import com.wise.escrow.domain.exception.InsufficientFundsException;
import java.math.BigDecimal;
import java.util.UUID;
import javax.money.MonetaryAmount;

/**
 * Ledger account aggregate. Only WISE_CLEARING system accounts may carry a
 * negative balance; every other type enforces balance >= 0 on debit.
 */
public class Account {

    private final UUID id;
    private final UUID entityId; // null = Wise system account
    private final AccountType type;
    private MonetaryAmount balance;

    public Account(
        UUID id,
        UUID entityId,
        AccountType type,
        MonetaryAmount balance
    ) {
        this.id = id;
        this.entityId = entityId;
        this.type = type;
        this.balance = balance;
    }

    public static Account open(
        UUID entityId,
        AccountType type,
        String currency
    ) {
        return new Account(
            UUID.randomUUID(),
            entityId,
            type,
            MoneyAmounts.zero(currency)
        );
    }

    public UUID id() {
        return id;
    }

    public UUID entityId() {
        return entityId;
    }

    public AccountType type() {
        return type;
    }

    public MonetaryAmount balance() {
        return balance;
    }

    public String currency() {
        return balance.getCurrency().getCurrencyCode();
    }

    public void debit(MonetaryAmount amount) {
        assertCurrency(amount);
        if (amount.isNegative()) {
            throw new IllegalArgumentException("debit amount must be >= 0");
        }
        MonetaryAmount next = balance.subtract(amount);
        if (type != AccountType.WISE_CLEARING && next.isNegative()) {
            throw new InsufficientFundsException(
                "Account %s balance %s cannot cover debit %s".formatted(
                    id,
                    balance,
                    amount
                )
            );
        }
        balance = next;
    }

    public void credit(MonetaryAmount amount) {
        assertCurrency(amount);
        if (amount.isNegative()) {
            throw new IllegalArgumentException("credit amount must be >= 0");
        }
        balance = balance.add(amount);
    }

    private void assertCurrency(MonetaryAmount amount) {
        if (!amount.getCurrency().equals(balance.getCurrency())) {
            throw new CurrencyMismatchException(
                "Currency %s does not match account currency %s".formatted(
                    amount.getCurrency(),
                    balance.getCurrency()
                )
            );
        }
    }
}
