package com.escrowrail.domain.model;

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
    boolean onboardingCompleted,
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
            false,
            Instant.now()
        );
    }

    public AppUser verified() {
        return new AppUser(
            id, email, passwordHash, entityId, active, true, UserStatus.ACTIVE,
            onboardingCompleted, createdAt
        );
    }

    public AppUser passwordChanged(String newPasswordHash) {
        return new AppUser(
            id, email, newPasswordHash, entityId, active, emailVerified, status,
            onboardingCompleted, createdAt
        );
    }

    public AppUser onboarded() {
        return new AppUser(
            id, email, passwordHash, entityId, active, emailVerified, status,
            true, createdAt
        );
    }
}
