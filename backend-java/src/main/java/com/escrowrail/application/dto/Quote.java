package com.escrowrail.application.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Funding quote: target amount, mid-market rate, and deposit breakdown. */
public record Quote(
    String sourceCurrency,
    String targetCurrency,
    BigDecimal targetAmount,
    BigDecimal midMarketRate,
    BigDecimal principal,
    BigDecimal platformFee,
    BigDecimal totalDeposit
) {
    /** Legacy-bank comparison: 3.8% spread + $150 SWIFT. */
    public record BankEstimate(
        BigDecimal spreadCost,
        BigDecimal swiftFee,
        BigDecimal totalCost
    ) {}

    /** 3.8% of deposit + fixed 150.00, HALF_EVEN cents, no FX conversion. */
    public BankEstimate traditionalBankEstimate() {
        BigDecimal spread = totalDeposit
            .multiply(new BigDecimal("0.038"))
            .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal swift = new BigDecimal("150.00");
        return new BankEstimate(spread, swift, spread.add(swift));
    }
}
