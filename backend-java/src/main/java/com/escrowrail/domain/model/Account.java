package com.escrowrail.domain.model;

import com.escrowrail.domain.exception.CurrencyMismatchException;
import com.escrowrail.domain.exception.InsufficientFundsException;
import java.math.BigDecimal;
import java.util.UUID;
import javax.money.MonetaryAmount;

/**
 * Ledger account. Only ESCROWRAIL_CLEARING may go negative; every other type
 * enforces balance >= 0 on debit.
 */
public class Account {

    private final UUID id;
    private final UUID entityId; // null = platform system account
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

    /** Opens a new account with a zero balance in the given currency. */
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

    /** Returns the account id. */
    public UUID id() {
        return id;
    }

    /** Returns the owning entity id, or null for a platform system account. */
    public UUID entityId() {
        return entityId;
    }

    /** Returns the account type (user, vault, clearing, fee). */
    public AccountType type() {
        return type;
    }

    /** Returns the current balance. */
    public MonetaryAmount balance() {
        return balance;
    }

    /** Returns the ISO currency code of the balance. */
    public String currency() {
        return balance.getCurrency().getCurrencyCode();
    }

    /**
     * Debit a nonnegative amount (zero allowed); only clearing may overdraw.
     *
     * @throws CurrencyMismatchException if the currencies differ
     * @throws IllegalArgumentException if the amount is negative or has fractional cents
     * @throws InsufficientFundsException if a non-clearing account cannot cover the debit
     */
    public void debit(MonetaryAmount amount) {
        assertCurrency(amount);
        assertCentScale(amount);
        if (amount.isNegative()) {
            throw new IllegalArgumentException("debit amount must be >= 0");
        }
        MonetaryAmount next = balance.subtract(amount);
        if (type != AccountType.ESCROWRAIL_CLEARING && next.isNegative()) {
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

    /**
     * Credit a nonnegative amount (zero allowed).
     *
     * @throws CurrencyMismatchException if the currencies differ
     * @throws IllegalArgumentException if the amount is negative or has fractional cents
     */
    public void credit(MonetaryAmount amount) {
        assertCurrency(amount);
        assertCentScale(amount);
        if (amount.isNegative()) {
            throw new IllegalArgumentException("credit amount must be >= 0");
        }
        balance = balance.add(amount);
    }

    /** Rejects >2dp amounts — Postgres numeric(20,2) would round them silently. */
    private void assertCentScale(MonetaryAmount amount) {
        int scale = amount
            .getNumber()
            .numberValue(BigDecimal.class)
            .stripTrailingZeros()
            .scale();
        if (scale > 2) {
            throw new IllegalArgumentException(
                "Amount scale %d exceeds cents: %s".formatted(scale, amount)
            );
        }
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
