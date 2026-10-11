package com.wise.escrow.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Registered user bound to exactly one tenant organization. */
public record AppUser(
        UUID id, String email, String passwordHash, UUID entityId, boolean active, Instant createdAt) {

    public static AppUser register(String email, String passwordHash, UUID entityId) {
        return new AppUser(
                UUID.randomUUID(), email.toLowerCase().strip(), passwordHash, entityId, true, Instant.now());
    }
}
