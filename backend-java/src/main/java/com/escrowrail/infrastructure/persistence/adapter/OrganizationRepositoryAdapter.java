package com.escrowrail.infrastructure.persistence.adapter;

import com.escrowrail.domain.model.Organization;
import com.escrowrail.domain.model.TaxId;
import com.escrowrail.domain.repository.OrganizationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.escrowrail.infrastructure.persistence.entity.OrganizationEntity;
import com.escrowrail.infrastructure.persistence.jpa.JpaOrganizationRepository;

@Component
class OrganizationRepositoryAdapter implements OrganizationRepository {

    private final JpaOrganizationRepository jpa;

    OrganizationRepositoryAdapter(JpaOrganizationRepository jpa) {
        this.jpa = jpa;
    }

    static Organization toDomain(OrganizationEntity e) {
        return new Organization(e.getId(), e.getLegalName(), new TaxId(e.getUboTaxId()), e.getCountry(), e.getCreatedAt());
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Organization> findById(UUID id) {
        return jpa.findById(id).map(OrganizationRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public Organization save(Organization org) {
        return toDomain(jpa.save(new OrganizationEntity(
                org.id(), org.legalName(), org.uboTaxId().value(), org.country(), org.createdAt())));
    }
}
