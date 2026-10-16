package com.escrowrail.infrastructure.persistence.entity;

import com.escrowrail.domain.model.AppUser.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "app_users",
    uniqueConstraints = @jakarta.persistence.UniqueConstraint(
        name = "app_users_email_uq",
        columnNames = { "email" }
    )
)
public class AppUserEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private UUID entityId;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private UserStatus status;

    @Column(nullable = false)
    private boolean onboardingCompleted;

    @Column(nullable = false)
    private Instant createdAt;

    /** JPA / reflective use only. */
    protected AppUserEntity() {}
    public AppUserEntity(
            UUID id,
            String email,
            String passwordHash,
            UUID entityId,
            boolean active,
            boolean emailVerified,
            UserStatus status,
            boolean onboardingCompleted,
            Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.entityId = entityId;
        this.active = active;
        this.emailVerified = emailVerified;
        this.status = status;
        this.onboardingCompleted = onboardingCompleted;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public UUID getEntityId() { return entityId; }
    /** Whether the account is active (verified and not disabled). */
    public boolean isActive() { return active; }
    /** Whether the email address was verified. */
    public boolean isEmailVerified() { return emailVerified; }
    public UserStatus getStatus() { return status; }
    /** Whether the onboarding setup window is closed. */
    public boolean isOnboardingCompleted() { return onboardingCompleted; }
    public Instant getCreatedAt() { return createdAt; }
}
