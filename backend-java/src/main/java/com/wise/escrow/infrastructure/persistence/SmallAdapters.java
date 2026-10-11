package com.escrowrail.escrow.infrastructure.persistence;

import com.escrowrail.escrow.domain.model.AppUser;
import com.escrowrail.escrow.domain.model.Organization;
import com.escrowrail.escrow.domain.model.TaxId;
import com.escrowrail.escrow.domain.model.VerificationToken;
import com.escrowrail.escrow.domain.repository.AppUserRepository;
import com.escrowrail.escrow.domain.repository.IdempotencyRecordRepository;
import com.escrowrail.escrow.domain.repository.LedgerRepository;
import com.escrowrail.escrow.domain.repository.OrganizationRepository;
import com.escrowrail.escrow.domain.repository.VerificationTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class OrganizationRepositoryAdapter implements OrganizationRepository {

    private final JpaOrganizationRepository jpa;

    OrganizationRepositoryAdapter(JpaOrganizationRepository jpa) {
        this.jpa = jpa;
    }

    static Organization toDomain(OrganizationEntity e) {
        return new Organization(e.getId(), e.getLegalName(), new TaxId(e.getUboTaxId()), e.getCountry(), e.getCreatedAt());
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Organization> findById(UUID id) {
        return jpa.findById(id).map(OrganizationRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public Organization save(Organization org) {
        return toDomain(jpa.save(new OrganizationEntity(
                org.id(), org.legalName(), org.uboTaxId().value(), org.country(), org.createdAt())));
    }
}

@Component
class AppUserRepositoryAdapter implements AppUserRepository {

    private final JpaAppUserRepository jpa;

    AppUserRepositoryAdapter(JpaAppUserRepository jpa) {
        this.jpa = jpa;
    }

    static AppUser toDomain(AppUserEntity e) {
        return new AppUser(
                e.getId(), e.getEmail(), e.getPasswordHash(), e.getEntityId(),
                e.isActive(), e.isEmailVerified(), e.getStatus(),
                e.isOnboardingCompleted(), e.getCreatedAt());
    }

    @Override
    /** {@inheritDoc} */
    public Optional<AppUser> findById(UUID id) {
        return jpa.findById(id).map(AppUserRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public Optional<AppUser> findByEmail(String email) {
        return jpa.findByEmail(email.strip().toLowerCase(java.util.Locale.ROOT)).map(AppUserRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public AppUser save(AppUser user) {
        return toDomain(jpa.save(new AppUserEntity(
                user.id(), user.email(), user.passwordHash(), user.entityId(),
                user.active(), user.emailVerified(), user.status(),
                user.onboardingCompleted(), user.createdAt())));
    }
}

@Component
class VerificationTokenRepositoryAdapter implements VerificationTokenRepository {

    private final JpaEmailVerificationTokenRepository jpa;

    VerificationTokenRepositoryAdapter(JpaEmailVerificationTokenRepository jpa) {
        this.jpa = jpa;
    }

    static VerificationToken toDomain(EmailVerificationTokenEntity e) {
        return new VerificationToken(
                e.getId(), e.getUserId(), e.getTokenHash(),
                e.getExpiresAt(), e.getUsedAt(), e.getCreatedAt());
    }

    @Override
    /** {@inheritDoc} */
    public Optional<VerificationToken> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(VerificationTokenRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public List<VerificationToken> findUnusedByUserId(UUID userId) {
        return jpa.findByUserIdAndUsedAtIsNull(userId).stream()
                .map(VerificationTokenRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    /** {@inheritDoc} */
    public Optional<VerificationToken> findLatestByUserId(UUID userId) {
        return jpa.findTopByUserIdOrderByCreatedAtDesc(userId)
                .map(VerificationTokenRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public VerificationToken save(VerificationToken token) {
        return toDomain(jpa.save(new EmailVerificationTokenEntity(
                token.id(), token.userId(), token.tokenHash(),
                token.expiresAt(), token.usedAt(), token.createdAt())));
    }
}

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
        Instant createdAt = Instant.now();
        // Cryptographic audit chain: each entry links to the previous
        // head, so tampering with any row invalidates everything after
        // it. The chain row is locked last, after account locks, in every
        // workflow — a consistent global order, so no deadlock cycle.
        LedgerChainEntity head = chain
                .findByIdForUpdate((short) 1)
                .orElseThrow(() -> new IllegalStateException(
                        "ledger_chain head row missing"));
        String hash = sha256(
                head.getCurrentHash()
                        + id
                        + debitAccountId
                        + creditAccountId
                        + amount.toPlainString()
                        + createdAt);
        head.setCurrentHash(hash);
        chain.save(head);
        return toDomain(jpa.save(new LedgerTransactionEntity(
                id,
                debitAccountId,
                creditAccountId,
                amount,
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

@Component
class IdempotencyRecordRepositoryAdapter implements IdempotencyRecordRepository {

    private final JpaIdempotencyRecordRepository jpa;

    IdempotencyRecordRepositoryAdapter(JpaIdempotencyRecordRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Record> findByKey(String key) {
        return jpa.findByIdempotencyKey(key).map(e -> new Record(
                e.getId(), e.getIdempotencyKey(), e.getResponseStatus(), e.getResponseBody(), e.getCreatedAt()));
    }

    @Override
    /** {@inheritDoc} */
    public Record save(Record record) {
        IdempotencyRecordEntity e = jpa.save(new IdempotencyRecordEntity(
                record.id(), record.idempotencyKey(), record.responseStatus(), record.responseBody(), record.createdAt()));
        return new Record(e.getId(), e.getIdempotencyKey(), e.getResponseStatus(), e.getResponseBody(), e.getCreatedAt());
    }

    @Override
    /** {@inheritDoc} */
    public void delete(Record record) {
        jpa.deleteById(record.id());
    }
}
