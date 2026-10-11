package com.wise.escrow.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Registered user bound to exactly one tenant organization. */
public record AppUser(
    UUID id,
    String email,
    String passwordHash,
    UUID entityId,
    boolean active,
    Instant createdAt
) {
    /** R. */
    public static AppUser register(
        String email,
        String passwordHash,
        UUID entityId
    ) {
        return new AppUser(
            UUID.randomUUID(),
            email.strip().toLowerCase(java.util.Locale.ROOT),
            passwordHash,
            entityId,
            true,
            Instant.now()
        );
    }
}
