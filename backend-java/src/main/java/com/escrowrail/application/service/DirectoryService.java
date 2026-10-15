package com.escrowrail.application.service;

import com.escrowrail.domain.model.DirectoryEntry;
import com.escrowrail.domain.repository.DirectoryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member directory: find counterparties by legal name or email. Verified
 * members only, requester excluded — filtering happens in the query.
 */
@Service
public class DirectoryService {

    private static final int MIN_QUERY = 2;
    private static final int MAX_RESULTS = 20;

    private final DirectoryRepository directory;
    public DirectoryService(DirectoryRepository directory) {
        this.directory = directory;
    }

    /** Searches verified members by email or organization legal name. */
    @Transactional(readOnly = true)
    public List<DirectoryEntry> search(String query, UUID requesterEntityId) {
        String q = query == null ? "" : query.strip();
        if (q.length() < MIN_QUERY) {
            return List.of();
        }
        return directory.searchMembers(q, requesterEntityId, MAX_RESULTS);
    }
}
