package com.escrowrail.escrow.domain.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerRepository {
    record LedgerEntry(
        UUID id,
        UUID debitAccountId,
        UUID creditAccountId,
        BigDecimal amount,
        String currency,
        String description,
        UUID contractId,
        Instant createdAt
    ) {}

    LedgerEntry record(
        UUID debitAccountId,
        UUID creditAccountId,
        BigDecimal amount,
        String currency,
        String description,
        UUID contractId
    );

    List<LedgerEntry> findByContractIdOrderByCreatedAt(UUID contractId);

    List<LedgerEntry> findByAccountId(UUID accountId);
}
