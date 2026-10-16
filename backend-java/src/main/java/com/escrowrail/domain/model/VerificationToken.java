package com.escrowrail.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Single-use email verification token. Only the SHA-256 hash is persisted — the raw token travels exclusively inside the verification link. */
public record VerificationToken(
    UUID id,
    UUID userId,
    String tokenHash,
    Instant expiresAt,
    Instant usedAt,
    Instant createdAt
) {
    public static final long TTL_SECONDS = 24 * 3600;

    public static VerificationToken issue(UUID userId, String tokenHash) {
        Instant now = Instant.now();
        return new VerificationToken(
            UUID.randomUUID(), userId, tokenHash, now.plusSeconds(TTL_SECONDS), null, now
        );
    }

    /** Whether the token can still be consumed. */
    public boolean usable() {
        return usedAt == null && expiresAt.isAfter(Instant.now());
    }

    public VerificationToken consumed() {
        return new VerificationToken(id, userId, tokenHash, expiresAt, Instant.now(), createdAt);
    }
}
