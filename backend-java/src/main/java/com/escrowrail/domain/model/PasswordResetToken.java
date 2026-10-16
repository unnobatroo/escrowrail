package com.escrowrail.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Single-use reset token: only the SHA-256 hash persists — the raw token travels in the link. 1h TTL. */
public record PasswordResetToken(
    UUID id,
    UUID userId,
    String tokenHash,
    Instant expiresAt,
    Instant usedAt,
    Instant createdAt
) {
    public static final long TTL_SECONDS = 3600;

    public static PasswordResetToken issue(UUID userId, String tokenHash) {
        Instant now = Instant.now();
        return new PasswordResetToken(
            UUID.randomUUID(), userId, tokenHash, now.plusSeconds(TTL_SECONDS), null, now
        );
    }

    /** Whether the token can still be consumed. */
    public boolean usable() {
        return usedAt == null && expiresAt.isAfter(Instant.now());
    }

    public PasswordResetToken consumed() {
        return new PasswordResetToken(id, userId, tokenHash, expiresAt, Instant.now(), createdAt);
    }
}
