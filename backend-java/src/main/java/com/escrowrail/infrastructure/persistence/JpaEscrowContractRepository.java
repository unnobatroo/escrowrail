package com.escrowrail.infrastructure.persistence;

import com.escrowrail.domain.model.EscrowStatus;
import com.escrowrail.domain.model.TrackingType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

interface JpaEscrowContractRepository extends JpaRepository<EscrowContractEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT c FROM EscrowContractEntity c WHERE c.id = :id")
    Optional<EscrowContractEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT c FROM EscrowContractEntity c WHERE c.id = :id AND (c.buyerId = :entityId OR c.sellerId = :entityId)")
    Optional<EscrowContractEntity> findByIdForEntity(
            @Param("id") UUID id, @Param("entityId") UUID entityId);

    @Query("SELECT c FROM EscrowContractEntity c WHERE c.trackingType = :type AND c.trackingKey = :key AND c.status = 'FUNDED'")
    List<EscrowContractEntity> findFundedByTracking(
            @Param("type") TrackingType type, @Param("key") String key);

    @Query("SELECT c FROM EscrowContractEntity c WHERE c.buyerId = :entityId OR c.sellerId = :entityId ORDER BY c.createdAt DESC")
    List<EscrowContractEntity> findAllForEntity(@Param("entityId") UUID entityId);

    @Query("SELECT c FROM EscrowContractEntity c WHERE (c.buyerId = :entityId OR c.sellerId = :entityId) AND c.status = :status ORDER BY c.createdAt DESC")
    List<EscrowContractEntity> findAllForEntityAndStatus(
            @Param("entityId") UUID entityId, @Param("status") EscrowStatus status);

    @Query("SELECT c FROM EscrowContractEntity c WHERE c.invitedEmail = :email AND c.status = 'INVITED'")
    List<EscrowContractEntity> findPendingInvites(@Param("email") String email);
}
