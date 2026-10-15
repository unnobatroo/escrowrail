package com.escrowrail.domain.repository;

import com.escrowrail.domain.model.DirectoryEntry;
import java.util.List;
import java.util.UUID;

public interface DirectoryRepository {

    /** Verified members matching the query, excluding the requester's entity. */
    List<DirectoryEntry> searchMembers(String query, UUID requesterEntityId, int limit);
}
