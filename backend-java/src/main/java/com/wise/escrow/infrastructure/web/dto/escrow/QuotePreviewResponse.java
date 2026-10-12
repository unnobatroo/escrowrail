package com.wise.escrow.infrastructure.web.dto.escrow;

import java.math.BigDecimal;

/** Deposit quote with the traditional-bank cost comparison. */
public record QuotePreviewResponse(
    String sourceCurrency,
    String targetCurrency,
    BigDecimal targetAmount,
    BigDecimal midMarketRate,
    BigDecimal principal,
    BigDecimal wiseFee,
    BigDecimal totalDeposit,
    BigDecimal bankSpreadCost,
    BigDecimal bankSwiftFee,
    BigDecimal bankTotalCost,
    BigDecimal customerSavings
) {}
