package com.escrowrail.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use password reset token. Only the SHA-256 hash is persisted —
 * the raw token travels exclusively inside the reset link. One-hour TTL:
 * a reset link is more sensitive than a verification link.
 */
public record PasswordResetToken(
    UUID id,
    UUID userId,
    String tokenHash,
    Instant expiresAt,
    Instant usedAt,
    Instant createdAt
) {
    public static final long TTL_SECONDS = 3600;

    /** Creates a fresh 1h token for {@code userId} from a pre-computed hash. */
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

    /** Returns a copy consumed at now. */
    public PasswordResetToken consumed() {
        return new PasswordResetToken(id, userId, tokenHash, expiresAt, Instant.now(), createdAt);
    }
}
