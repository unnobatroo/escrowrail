package com.escrowrail.escrow.infrastructure.web.dto.profile;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;

/** Self-service profile update plus one-time onboarding deposits. */
public record UpdateProfileRequest(
    @Size(max = 255) String legalEntityName,
    @Size(max = 64) String taxId,
    // One-time onboarding credit per currency, e.g. {"USD": 25000.00}.
    // Only currencies the platform actually clears are accepted.
    Map<@NotBlank @Size(max = 3) String,
        @DecimalMin("0.01") @Digits(integer = 18, fraction = 2) BigDecimal>
        initialDeposits
) {}
