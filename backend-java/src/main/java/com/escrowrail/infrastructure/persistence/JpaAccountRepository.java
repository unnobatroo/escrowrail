package com.escrowrail.infrastructure.persistence;

import com.escrowrail.domain.model.AccountType;
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

    List<AccountEntity> findByEntityIdIsNullAndType(AccountType type);
}
