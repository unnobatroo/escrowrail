package com.escrowrail.infrastructure.web.dto.escrow;

import java.util.UUID;

/** Resolved counterparty with the circular-ownership collision flag. */
public record CounterpartyResponse(
    String email,
    UUID entityId,
    String legalName,
    String country,
    boolean sameUbo
) {}
