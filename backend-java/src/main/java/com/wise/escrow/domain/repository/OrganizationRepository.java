package com.wise.escrow.domain.repository;

import com.wise.escrow.domain.model.Organization;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository {

    Optional<Organization> findById(UUID id);

    Organization save(Organization organization);
}
