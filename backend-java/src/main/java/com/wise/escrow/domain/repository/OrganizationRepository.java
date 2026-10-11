package com.escrowrail.escrow.domain.repository;

import com.escrowrail.escrow.domain.model.Organization;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository {

    Optional<Organization> findById(UUID id);

    Organization save(Organization organization);
}
