package com.escrowrail.infrastructure.web.dto.escrow;

import java.math.BigDecimal;

/** Deposit quote with the traditional-bank cost comparison. */
public record QuotePreviewResponse(
    String sourceCurrency,
    String targetCurrency,
    BigDecimal targetAmount,
    BigDecimal midMarketRate,
    BigDecimal principal,
    BigDecimal platformFee,
    BigDecimal totalDeposit,
    BigDecimal bankSpreadCost,
    BigDecimal bankSwiftFee,
    BigDecimal bankTotalCost,
    BigDecimal customerSavings
) {}
