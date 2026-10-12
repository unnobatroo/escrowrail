package com.wise.escrow.application.service;

import com.wise.escrow.domain.model.DirectoryEntry;
import com.wise.escrow.domain.repository.DirectoryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared member directory — lets registered users find each other by legal
 * name or email so trades are created between real members, not blind
 * addresses. Filtering (verified members only, requester excluded, result
 * cap) happens in the database query itself.
 */
@Service
public class DirectoryService {

    private static final int MIN_QUERY = 2;
    private static final int MAX_RESULTS = 20;

    private final DirectoryRepository directory;

    /** Creates a new DirectoryService. */
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
