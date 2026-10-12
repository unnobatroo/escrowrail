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
    boolean emailVerified,
    UserStatus status,
    Instant createdAt
) {
    /** Lifecycle gate — verification transitions PENDING -> ACTIVE. */
    public enum UserStatus {
        PENDING_VERIFICATION,
        ACTIVE,
        SUSPENDED
    }

    /** Registers a new user awaiting email verification. */
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
            false,
            UserStatus.PENDING_VERIFICATION,
            Instant.now()
        );
    }

    /** Returns a copy marked verified and active. */
    public AppUser verified() {
        return new AppUser(
            id, email, passwordHash, entityId, active, true, UserStatus.ACTIVE, createdAt
        );
    }
}
