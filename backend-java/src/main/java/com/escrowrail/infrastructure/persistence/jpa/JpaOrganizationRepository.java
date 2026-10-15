package com.escrowrail.infrastructure.persistence.jpa;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.escrowrail.infrastructure.persistence.entity.OrganizationEntity;

public interface JpaOrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {}
