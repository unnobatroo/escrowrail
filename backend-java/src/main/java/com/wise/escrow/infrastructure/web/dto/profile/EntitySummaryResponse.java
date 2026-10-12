package com.escrowrail.escrow.infrastructure.web.dto.profile;

import java.util.UUID;

/** The organization entity a user belongs to. */
public record EntitySummaryResponse(
    UUID id,
    String legalName,
    String uboTaxId,
    String country
) {}
