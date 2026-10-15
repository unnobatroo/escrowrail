package com.escrowrail.application.service;

import com.escrowrail.domain.exception.UnsupportedCurrencyPairException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Spot-rate provider port. */
public interface FxRateProvider {
    /** Units of target currency per 1 unit of source currency. */
    BigDecimal getRate(String sourceCurrency, String targetCurrency);
}

/**
 * Static mid-market table; USD->EUR fixed at 1/1.08.
 */
@Component
class StaticFxRateProvider implements FxRateProvider {

    private static final Map<String, BigDecimal> USD_RATES = Map.of(
        "EUR",
        new BigDecimal("0.9259259259259259"),
        "USD",
        BigDecimal.ONE
    );

    /**
     * Returns target units per source unit from the static USD/EUR table, ignoring
     * case. Identical codes return one without checking whether the currency exists.
     *
     * @throws UnsupportedCurrencyPairException if different codes are outside the table
     */
    @Override
    public BigDecimal getRate(String source, String target) {
        String src = source.toUpperCase(java.util.Locale.ROOT);
        String dst = target.toUpperCase(java.util.Locale.ROOT);
        if (src.equals(dst)) {
            return BigDecimal.ONE;
        }
        if ("USD".equals(src) && USD_RATES.containsKey(dst)) {
            return USD_RATES.get(dst);
        }
        if (USD_RATES.containsKey(src) && "USD".equals(dst)) {
            return BigDecimal.ONE.divide(
                USD_RATES.get(src),
                8,
                RoundingMode.HALF_EVEN
            );
        }
        if (USD_RATES.containsKey(src) && USD_RATES.containsKey(dst)) {
            // Cross via USD.
            return getRate(src, "USD").multiply(getRate("USD", dst));
        }
        throw new UnsupportedCurrencyPairException(
            "No rate for %s/%s".formatted(src, dst)
        );
    }
}
