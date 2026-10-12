package com.escrowrail.escrow.domain.model;

import java.util.UUID;

/** A verified platform member as seen through the shared directory. */
public record DirectoryEntry(
    String email,
    UUID entityId,
    String legalName,
    String country
) {}
