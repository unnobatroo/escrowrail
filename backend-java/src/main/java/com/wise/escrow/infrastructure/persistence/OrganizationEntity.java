package com.wise.escrow.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class OrganizationEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String legalName;

    @Column(nullable = false)
    private String uboTaxId;

    @Column(nullable = false)
    private String country;

    @Column(nullable = false)
    private Instant createdAt;

    protected OrganizationEntity() {}

    public OrganizationEntity(UUID id, String legalName, String uboTaxId, String country, Instant createdAt) {
        this.id = id;
        this.legalName = legalName;
        this.uboTaxId = uboTaxId;
        this.country = country;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getLegalName() { return legalName; }
    public String getUboTaxId() { return uboTaxId; }
    public String getCountry() { return country; }
    public Instant getCreatedAt() { return createdAt; }
}
