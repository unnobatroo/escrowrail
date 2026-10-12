package com.wise.escrow.infrastructure.web.dto.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/** A currency account and its current balance. */
public record AccountBalanceResponse(
    UUID id,
    UUID entityId,
    String accountType,
    String currency,
    BigDecimal balance
) {}
