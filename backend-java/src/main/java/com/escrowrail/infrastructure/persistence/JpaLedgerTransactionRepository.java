package com.escrowrail.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface JpaLedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {

    List<LedgerTransactionEntity> findByContractIdOrderByCreatedAt(UUID contractId);

    @Query("SELECT t FROM LedgerTransactionEntity t WHERE t.debitAccountId = :accountId OR t.creditAccountId = :accountId ORDER BY t.createdAt")
    List<LedgerTransactionEntity> findByAccountId(@Param("accountId") UUID accountId);
}
