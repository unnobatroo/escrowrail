package com.escrowrail.infrastructure.persistence;

import com.escrowrail.domain.repository.LedgerRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class LedgerRepositoryAdapter implements LedgerRepository {

    private final JpaLedgerTransactionRepository jpa;
    private final JpaLedgerChainRepository chain;

    LedgerRepositoryAdapter(
            JpaLedgerTransactionRepository jpa,
            JpaLedgerChainRepository chain) {
        this.jpa = jpa;
        this.chain = chain;
    }

    static LedgerEntry toDomain(LedgerTransactionEntity e) {
        return new LedgerEntry(
                e.getId(),
                e.getDebitAccountId(),
                e.getCreditAccountId(),
                e.getAmount(),
                e.getCurrency(),
                e.getDescription(),
                e.getContractId(),
                e.getCreatedAt(),
                e.getCurrentHash());
    }

    @Override
    /** {@inheritDoc} */
    public LedgerEntry record(
            UUID debitAccountId,
            UUID creditAccountId,
            java.math.BigDecimal amount,
            String currency,
            String description,
            UUID contractId) {
        UUID id = UUID.randomUUID();
        // Micros: Postgres timestamp precision — anything finer would not
        // survive the round trip and recompute-verification would diverge.
        Instant createdAt = Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        java.math.BigDecimal canonicalAmount =
                amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        // Cryptographic audit chain: each entry links to the previous
        // head, so tampering with any row invalidates everything after
        // it. The chain row is locked last, after account locks, in every
        // workflow — a consistent global order, so no deadlock cycle.
        // Every persisted field is hashed with a separator — a field left
        // out (or unseparated) could be mutated without breaking the chain.
        LedgerChainEntity head = chain
                .findByIdForUpdate((short) 1)
                .orElseThrow(() -> new IllegalStateException(
                        "ledger_chain head row missing"));
        String hash = sha256(String.join("|",
                head.getCurrentHash(),
                id.toString(),
                debitAccountId.toString(),
                creditAccountId.toString(),
                canonicalAmount.toPlainString(),
                currency,
                String.valueOf(contractId),
                String.valueOf(description),
                createdAt.toString()));
        head.setCurrentHash(hash);
        chain.save(head);
        return toDomain(jpa.save(new LedgerTransactionEntity(
                id,
                debitAccountId,
                creditAccountId,
                canonicalAmount,
                currency,
                description,
                contractId,
                createdAt,
                hash)));
    }

    private static String sha256(String input) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    /** {@inheritDoc} */
    public List<LedgerEntry> findByContractIdOrderByCreatedAt(UUID contractId) {
        return jpa.findByContractIdOrderByCreatedAt(contractId).stream()
                .map(LedgerRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    /** {@inheritDoc} */
    public List<LedgerEntry> findByAccountId(UUID accountId) {
        return jpa.findByAccountId(accountId).stream()
                .map(LedgerRepositoryAdapter::toDomain)
                .toList();
    }
}
