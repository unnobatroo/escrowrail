package com.escrowrail.infrastructure.web.dto.escrow;

import com.escrowrail.domain.model.TrackingType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Trade creation: counterparty email, invoice amount, and milestone proof. */
public record CreateEscrowTradeRequest(
    @NotBlank @Email String counterpartyEmail,
    @NotNull @DecimalMin("0.01") BigDecimal targetAmount,
    String targetCurrency,
    String sourceCurrency,
    @NotNull TrackingType trackingType,
    @NotBlank String trackingKey
) {}
