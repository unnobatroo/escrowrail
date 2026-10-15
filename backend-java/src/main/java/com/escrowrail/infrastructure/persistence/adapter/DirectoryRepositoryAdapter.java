package com.escrowrail.infrastructure.persistence.adapter;

import com.escrowrail.domain.model.DirectoryEntry;
import com.escrowrail.domain.repository.DirectoryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import com.escrowrail.infrastructure.persistence.jpa.JpaDirectoryRepository;

@Component
class DirectoryRepositoryAdapter implements DirectoryRepository {

    private final JpaDirectoryRepository jpa;

    DirectoryRepositoryAdapter(JpaDirectoryRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    /** {@inheritDoc} */
    public List<DirectoryEntry> searchMembers(String query, UUID requesterEntityId, int limit) {
        return jpa.searchMembers(query, requesterEntityId, PageRequest.of(0, limit));
    }
}
