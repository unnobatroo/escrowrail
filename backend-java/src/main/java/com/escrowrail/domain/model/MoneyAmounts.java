package com.escrowrail.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import javax.money.Monetary;
import javax.money.MonetaryAmount;
import org.javamoney.moneta.Money;

/**
 * JSR-354 money helpers; all amounts round to cents HALF_EVEN — the single
 * rounding rule platform-wide.
 */
public final class MoneyAmounts {

    public static final BigDecimal CENT = new BigDecimal("0.01");

    private MoneyAmounts() {}

    /**
     * Amount without cent rounding; use {@link #cents} when needed.
     *
     * @throws javax.money.UnknownCurrencyException if the currency code is unknown
     */
    public static MonetaryAmount of(BigDecimal amount, String currency) {
        return Money.of(amount, Monetary.getCurrency(currency));
    }

    /**
     * Parses a decimal amount without cent rounding.
     *
     * @throws NumberFormatException if the amount is not a valid decimal
     * @throws javax.money.UnknownCurrencyException if the currency code is unknown
     */
    public static MonetaryAmount of(String amount, String currency) {
        return of(new BigDecimal(amount), currency);
    }

    /** Returns a zero amount in the given currency. */
    public static MonetaryAmount zero(String currency) {
        return of(BigDecimal.ZERO, currency);
    }

    /** Amount rounded to cents, HALF_EVEN, same currency. */
    public static MonetaryAmount cents(MonetaryAmount amount) {
        return Money.of(
            amount
                .getNumber()
                .numberValue(BigDecimal.class)
                .setScale(2, RoundingMode.HALF_EVEN),
            amount.getCurrency()
        );
    }
}
