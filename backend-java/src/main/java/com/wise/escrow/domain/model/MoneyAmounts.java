package com.wise.escrow.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import javax.money.Monetary;
import javax.money.MonetaryAmount;
import org.javamoney.moneta.Money;

/**
 * Money helpers on top of the JSR-354 implementation. All amounts are
 * quantized to cents with HALF_EVEN (banker's rounding) — the single
 * rounding rule for the whole platform.
 */
public final class MoneyAmounts {

    public static final BigDecimal CENT = new BigDecimal("0.01");

    private MoneyAmounts() {}

    public static MonetaryAmount of(BigDecimal amount, String currency) {
        return Money.of(amount, Monetary.getCurrency(currency));
    }

    public static MonetaryAmount of(String amount, String currency) {
        return of(new BigDecimal(amount), currency);
    }

    public static MonetaryAmount zero(String currency) {
        return of(BigDecimal.ZERO, currency);
    }

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
