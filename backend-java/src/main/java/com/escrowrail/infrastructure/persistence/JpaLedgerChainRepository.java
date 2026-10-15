package com.escrowrail.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface JpaLedgerChainRepository extends JpaRepository<LedgerChainEntity, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM LedgerChainEntity c WHERE c.id = :id")
    Optional<LedgerChainEntity> findByIdForUpdate(@Param("id") short id);
}
