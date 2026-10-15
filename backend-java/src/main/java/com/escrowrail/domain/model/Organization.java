package com.escrowrail.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Tenant organization aggregate — the unit of multi-tenant isolation. */
public record Organization(
    UUID id,
    String legalName,
    TaxId uboTaxId,
    String country,
    Instant createdAt
) {
    /** Creates a new organization with a normalized UBO tax id. */
    public static Organization register(
        String legalName,
        String uboTaxId,
        String country
    ) {
        return new Organization(
            UUID.randomUUID(),
            legalName,
            new TaxId(uboTaxId),
            country,
            Instant.now()
        );
    }
}
