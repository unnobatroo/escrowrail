package com.escrowrail.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.QueryHints;
import com.escrowrail.infrastructure.persistence.entity.AppUserEntity;

public interface JpaAppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    Optional<AppUserEntity> findByEmail(String email);

    /** Row-locked lookup serializing concurrent reset/cooldown checks. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    Optional<AppUserEntity> findWithLockByEmail(String email);

    /** Row-locked lookup serializing concurrent onboarding-deposit checks. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT u FROM AppUserEntity u WHERE u.id = :id")
    Optional<AppUserEntity> findWithLockById(@Param("id") UUID id);
}
