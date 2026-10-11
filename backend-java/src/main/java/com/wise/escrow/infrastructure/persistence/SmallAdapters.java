package com.wise.escrow.infrastructure.persistence;

import com.wise.escrow.domain.model.AppUser;
import com.wise.escrow.domain.model.Organization;
import com.wise.escrow.domain.model.TaxId;
import com.wise.escrow.domain.repository.AppUserRepository;
import com.wise.escrow.domain.repository.IdempotencyRecordRepository;
import com.wise.escrow.domain.repository.LedgerRepository;
import com.wise.escrow.domain.repository.OrganizationRepository;
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
    public Optional<Organization> findById(UUID id) {
        return jpa.findById(id).map(OrganizationRepositoryAdapter::toDomain);
    }

    @Override
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
        return new AppUser(e.getId(), e.getEmail(), e.getPasswordHash(), e.getEntityId(), e.isActive(), e.getCreatedAt());
    }

    @Override
    public Optional<AppUser> findById(UUID id) {
        return jpa.findById(id).map(AppUserRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<AppUser> findByEmail(String email) {
        return jpa.findByEmail(email.strip().toLowerCase(java.util.Locale.ROOT)).map(AppUserRepositoryAdapter::toDomain);
    }

    @Override
    public AppUser save(AppUser user) {
        return toDomain(jpa.save(new AppUserEntity(
                user.id(), user.email(), user.passwordHash(), user.entityId(), user.active(), user.createdAt())));
    }
}

@Component
class LedgerRepositoryAdapter implements LedgerRepository {

    private final JpaLedgerTransactionRepository jpa;

    LedgerRepositoryAdapter(JpaLedgerTransactionRepository jpa) {
        this.jpa = jpa;
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
                e.getCreatedAt());
    }

    @Override
    public LedgerEntry record(
            UUID debitAccountId,
            UUID creditAccountId,
            java.math.BigDecimal amount,
            String currency,
            String description,
            UUID contractId) {
        return toDomain(jpa.save(new LedgerTransactionEntity(
                UUID.randomUUID(),
                debitAccountId,
                creditAccountId,
                amount,
                currency,
                description,
                contractId,
                Instant.now())));
    }

    @Override
    public List<LedgerEntry> findByContractIdOrderByCreatedAt(UUID contractId) {
        return jpa.findByContractIdOrderByCreatedAt(contractId).stream()
                .map(LedgerRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
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
    public Optional<Record> findByKey(String key) {
        return jpa.findByIdempotencyKey(key).map(e -> new Record(
                e.getId(), e.getIdempotencyKey(), e.getResponseStatus(), e.getResponseBody(), e.getCreatedAt()));
    }

    @Override
    public Record save(Record record) {
        IdempotencyRecordEntity e = jpa.save(new IdempotencyRecordEntity(
                record.id(), record.idempotencyKey(), record.responseStatus(), record.responseBody(), record.createdAt()));
        return new Record(e.getId(), e.getIdempotencyKey(), e.getResponseStatus(), e.getResponseBody(), e.getCreatedAt());
    }

    @Override
    public void delete(Record record) {
        jpa.deleteById(record.id());
    }
}
