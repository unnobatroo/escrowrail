package com.wise.escrow.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecordEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(nullable = false)
    private int responseStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {}

    public IdempotencyRecordEntity(
            UUID id, String idempotencyKey, int responseStatus, String responseBody, Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
}
