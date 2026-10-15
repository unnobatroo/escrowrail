package com.escrowrail.infrastructure.persistence.jpa;

import com.escrowrail.domain.model.DirectoryEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.escrowrail.infrastructure.persistence.entity.AppUserEntity;
import com.escrowrail.infrastructure.persistence.entity.OrganizationEntity;

public interface JpaDirectoryRepository extends Repository<OrganizationEntity, UUID> {

    /**
     * Verified members discoverable by email or legal name — one joined query,
     * so the directory is always just the live user + organization tables.
     */
    @Query("""
            SELECT new com.escrowrail.domain.model.DirectoryEntry(
                u.email, o.id, o.legalName, o.country)
            FROM AppUserEntity u, OrganizationEntity o
            WHERE u.entityId = o.id
              AND u.emailVerified = true
              AND u.entityId <> :requesterEntityId
              AND (LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(o.legalName) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY o.legalName
            """)
    List<DirectoryEntry> searchMembers(
            @Param("query") String query,
            @Param("requesterEntityId") UUID requesterEntityId,
            Pageable pageable);
}
