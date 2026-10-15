package com.escrowrail.infrastructure.web.dto.escrow;

import java.util.UUID;

/** A verified member of the shared counterparty directory. */
public record DirectoryMemberResponse(
    String email,
    UUID entityId,
    String legalName,
    String country
) {}
