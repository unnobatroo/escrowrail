package com.escrowrail.application.service;

import com.escrowrail.application.dto.Quote;
import com.escrowrail.domain.exception.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;
import com.escrowrail.application.port.FxRateProvider;

/** Deposit pricing: fee rate, cent rounding, and the coverage loop. */
@Component
public class QuoteCalculator {

    /** Platform conversion fee, taken in source currency at funding time. */
    public static final BigDecimal CONVERSION_FEE_RATE = new BigDecimal(
        "0.005"
    );

    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final BigDecimal MIN_TOTAL = new BigDecimal("0.02");

    private final FxRateProvider fx;

    public QuoteCalculator(FxRateProvider fx) {
        this.fx = fx;
    }

    /** Quotes the minimal cent-rounded source deposit whose net conversion covers the target. Fee: 0.5%, HALF_EVEN, 0.01 minimum. The rate is not reserved. */
    public Quote quote(
        String sourceCurrency,
        String targetCurrency,
        BigDecimal targetAmount
    ) {
        if (targetAmount == null || targetAmount.signum() <= 0) {
            throw new ValidationException("targetAmount must be positive");
        }
        if (targetAmount.stripTrailingZeros().scale() > 2) {
            throw new ValidationException(
                "Amounts must have at most 2 decimal places"
            );
        }
        BigDecimal rate = fx.getRate(sourceCurrency, targetCurrency);
        BigDecimal oneMinusFee = BigDecimal.ONE.subtract(CONVERSION_FEE_RATE);
        BigDecimal total = targetAmount
            .divide(oneMinusFee.multiply(rate), 8, RoundingMode.HALF_EVEN)
            .setScale(2, RoundingMode.FLOOR)
            .max(MIN_TOTAL);
        // Fee floors at one cent so sub-cent targets cannot round to zero.
        BigDecimal fee = feeFor(total);
        // Step down if HALF_EVEN rounding on fee/conversion already covers target at a lower cent.
        while (total.compareTo(MIN_TOTAL) > 0) {
            BigDecimal prevTotal = total.subtract(CENT);
            BigDecimal prevFee = feeFor(prevTotal);
            if (converted(prevTotal, prevFee, rate).compareTo(targetAmount) >= 0) {
                total = prevTotal;
                fee = prevFee;
            } else {
                break;
            }
        }
        // Step up until the net conversion covers the target amount.
        while (converted(total, fee, rate).compareTo(targetAmount) < 0) {
            total = total.add(CENT);
            fee = feeFor(total);
        }
        return new Quote(
            sourceCurrency,
            targetCurrency,
            targetAmount,
            rate,
            total.subtract(fee),
            fee,
            total
        );
    }

    private static BigDecimal feeFor(BigDecimal total) {
        return total
            .multiply(CONVERSION_FEE_RATE)
            .setScale(2, RoundingMode.HALF_EVEN)
            .max(CENT);
    }

    private static BigDecimal converted(
        BigDecimal total,
        BigDecimal fee,
        BigDecimal rate
    ) {
        return total
            .subtract(fee)
            .multiply(rate)
            .setScale(2, RoundingMode.HALF_EVEN);
    }
}
