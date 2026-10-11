package com.wise.escrow.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    private Instant createdAt;

    protected AppUserEntity() {}

    public AppUserEntity(
            UUID id, String email, String passwordHash, UUID entityId, boolean active, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.entityId = entityId;
        this.active = active;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public UUID getEntityId() { return entityId; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
