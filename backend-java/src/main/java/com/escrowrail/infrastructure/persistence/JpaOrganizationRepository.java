package com.escrowrail.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JpaOrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {}
