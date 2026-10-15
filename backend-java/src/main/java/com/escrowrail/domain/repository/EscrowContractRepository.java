package com.escrowrail.domain.repository;

import com.escrowrail.domain.model.EscrowContract;
import com.escrowrail.domain.model.EscrowStatus;
import com.escrowrail.domain.model.TrackingType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EscrowContractRepository {
    Optional<EscrowContract> findById(UUID id);

    /** PESSIMISTIC_WRITE lock on the contract row — inside a transaction. */
    Optional<EscrowContract> findByIdForUpdate(UUID id);

    /** Tenant-scoped read: only parties to the contract may see it. */
    Optional<EscrowContract> findByIdForEntity(UUID id, UUID entityId);

    /** All FUNDED contracts on this tracking event — each settles. */
    List<EscrowContract> findFundedByTracking(
        TrackingType trackingType,
        String trackingKey
    );

    List<EscrowContract> findAllForEntity(UUID entityId);

    /** INVITED contracts awaiting a signup under this email. */
    List<EscrowContract> findPendingInvites(String invitedEmail);

    List<EscrowContract> findAllForEntityAndStatus(
        UUID entityId,
        EscrowStatus status
    );

    EscrowContract save(EscrowContract contract);
}
