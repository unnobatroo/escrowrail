package com.escrowrail.infrastructure.web.dto.escrow;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Funding deposit in the contract's source currency. */
public record FundEscrowTradeRequest(
    @NotNull @DecimalMin("0.01") BigDecimal depositAmount
) {}
