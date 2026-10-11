package com.escrowrail.escrow.domain.model;

import com.escrowrail.escrow.domain.exception.CurrencyMismatchException;
import com.escrowrail.escrow.domain.exception.InsufficientFundsException;
import java.math.BigDecimal;
import java.util.UUID;
import javax.money.MonetaryAmount;

/**
 * Ledger account aggregate. Only ESCROWRAIL_CLEARING system accounts may carry a
 * negative balance; every other type enforces balance >= 0 on debit.
 */
public class Account {

    private final UUID id;
    private final UUID entityId; // null = Escrowrail system account
    private final AccountType type;
    private MonetaryAmount balance;

    /** Creates a new Account. */
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

    /** O. */
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

    /** I. */
    public UUID id() {
        return id;
    }

    /** E id. */
    public UUID entityId() {
        return entityId;
    }

    /** T. */
    public AccountType type() {
        return type;
    }

    /** B. */
    public MonetaryAmount balance() {
        return balance;
    }

    /** C. */
    public String currency() {
        return balance.getCurrency().getCurrencyCode();
    }

    /**
     * Subtracts a nonnegative amount in this account's currency; zero is allowed.
     * Only clearing accounts may be overdrawn. Rejection leaves the balance unchanged.
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
     * Adds a nonnegative amount in this account's currency; zero is allowed.
     * Rejection leaves the balance unchanged.
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

    /**
     * Balances persist as numeric(20,2); an FX-derived amount carrying
     * more decimals would silently drift when Postgres rounds HALF_UP.
     */
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
