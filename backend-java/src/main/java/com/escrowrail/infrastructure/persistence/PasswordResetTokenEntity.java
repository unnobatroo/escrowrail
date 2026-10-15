package com.escrowrail.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Single-use password reset token row — stores the SHA-256 hash, never raw. */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetTokenEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    @Column(nullable = false)
    private Instant createdAt;

    /** For JPA / reflective use only. */
    protected PasswordResetTokenEntity() {}
    public PasswordResetTokenEntity(
            UUID id,
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            Instant usedAt,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.usedAt = usedAt;
        this.createdAt = createdAt;
    }

    /** Returns the id. */
    public UUID getId() { return id; }
    /** Returns the user id. */
    public UUID getUserId() { return userId; }
    /** Returns the token hash. */
    public String getTokenHash() { return tokenHash; }
    /** Returns the expires at. */
    public Instant getExpiresAt() { return expiresAt; }
    /** Returns the used at. */
    public Instant getUsedAt() { return usedAt; }
    /** Returns the created at. */
    public Instant getCreatedAt() { return createdAt; }
}
