package com.escrowrail.escrow.infrastructure.persistence;

import com.escrowrail.escrow.domain.model.AppUser.UserStatus;
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
    private Instant createdAt;

    /** For JPA / reflective use only. */
    protected AppUserEntity() {}

    /** Creates a new AppUserEntity. */
    public AppUserEntity(
            UUID id,
            String email,
            String passwordHash,
            UUID entityId,
            boolean active,
            boolean emailVerified,
            UserStatus status,
            Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.entityId = entityId;
        this.active = active;
        this.emailVerified = emailVerified;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Returns the id. */
    public UUID getId() { return id; }
    /** Returns the email. */
    public String getEmail() { return email; }
    /** Returns the password hash. */
    public String getPasswordHash() { return passwordHash; }
    /** Returns the entity id. */
    public UUID getEntityId() { return entityId; }
    /** Whether active. */
    public boolean isActive() { return active; }
    /** Whether the email address was verified. */
    public boolean isEmailVerified() { return emailVerified; }
    /** Returns the lifecycle status. */
    public UserStatus getStatus() { return status; }
    /** Returns the created at. */
    public Instant getCreatedAt() { return createdAt; }
}
