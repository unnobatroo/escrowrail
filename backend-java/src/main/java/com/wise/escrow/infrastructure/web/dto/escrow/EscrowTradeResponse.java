package com.escrowrail.escrow.infrastructure.web.dto.escrow;

import com.escrowrail.escrow.domain.model.EscrowStatus;
import com.escrowrail.escrow.domain.model.TrackingType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Contract wire view with the caller's role and funding snapshot. */
public record EscrowTradeResponse(
    UUID id,
    UUID buyerId,
    UUID sellerId,
    String buyerName,
    String sellerName,
    String role,
    EscrowStatus status,
    String targetCurrency,
    BigDecimal targetAmount,
    String sourceCurrency,
    BigDecimal depositAmount,
    BigDecimal lockedSpotRate,
    BigDecimal escrowedAmount,
    TrackingType trackingType,
    String trackingKey,
    Instant fundedAt,
    Instant createdAt,
    // Unbundled funding receipt — populated only on the fund response.
    BigDecimal grossDebited,
    BigDecimal wiseConversionFee,
    BigDecimal netVaulted
) {}
