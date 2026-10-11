package com.escrowrail.escrow.infrastructure.persistence;

import com.escrowrail.escrow.domain.model.AccountType;
import com.escrowrail.escrow.domain.model.EscrowStatus;
import com.escrowrail.escrow.domain.model.TrackingType;
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

interface JpaAccountRepository extends JpaRepository<AccountEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT a FROM AccountEntity a WHERE a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<AccountEntity> findByEntityIdAndTypeAndCurrency(
            UUID entityId, AccountType type, String currency);

    // Scalar id lookups: resolving an id must NOT put the entity in the
    // persistence context — a later FOR UPDATE load would otherwise
    // return the stale pre-lock snapshot.
    @Query("SELECT a.id FROM AccountEntity a WHERE a.entityId = :entityId AND a.type = :type AND a.currency = :currency")
    Optional<UUID> findIdByEntityIdAndTypeAndCurrency(
            @Param("entityId") UUID entityId,
            @Param("type") AccountType type,
            @Param("currency") String currency);

    @Query("SELECT a.id FROM AccountEntity a WHERE a.entityId IS NULL AND a.type = :type AND a.currency = :currency")
    Optional<UUID> findSystemAccountId(
            @Param("type") AccountType type, @Param("currency") String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT a FROM AccountEntity a WHERE a.entityId = :entityId AND a.type = :type AND a.currency = :currency")
    Optional<AccountEntity> findByEntityIdAndTypeAndCurrencyForUpdate(
            @Param("entityId") UUID entityId,
            @Param("type") AccountType type,
            @Param("currency") String currency);

    Optional<AccountEntity> findByEntityIdIsNullAndTypeAndCurrency(AccountType type, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT a FROM AccountEntity a WHERE a.entityId IS NULL AND a.type = :type AND a.currency = :currency")
    Optional<AccountEntity> findSystemForUpdate(
            @Param("type") AccountType type, @Param("currency") String currency);

    List<AccountEntity> findAllByEntityId(UUID entityId);
}

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
}

interface JpaOrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {}

interface JpaAppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    Optional<AppUserEntity> findByEmail(String email);
}

interface JpaLedgerChainRepository extends JpaRepository<LedgerChainEntity, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM LedgerChainEntity c WHERE c.id = :id")
    Optional<LedgerChainEntity> findByIdForUpdate(@Param("id") short id);
}

interface JpaLedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {

    List<LedgerTransactionEntity> findByContractIdOrderByCreatedAt(UUID contractId);

    @Query("SELECT t FROM LedgerTransactionEntity t WHERE t.debitAccountId = :accountId OR t.creditAccountId = :accountId ORDER BY t.createdAt")
    List<LedgerTransactionEntity> findByAccountId(@Param("accountId") UUID accountId);
}

interface JpaIdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {

    Optional<IdempotencyRecordEntity> findByIdempotencyKey(String idempotencyKey);
}

interface JpaEmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationTokenEntity, UUID> {

    Optional<EmailVerificationTokenEntity> findByTokenHash(String tokenHash);

    List<EmailVerificationTokenEntity> findByUserIdAndUsedAtIsNull(UUID userId);

    Optional<EmailVerificationTokenEntity> findTopByUserIdOrderByCreatedAtDesc(UUID userId);
}
