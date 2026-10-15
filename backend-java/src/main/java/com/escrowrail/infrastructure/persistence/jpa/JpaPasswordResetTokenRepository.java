package com.escrowrail.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import com.escrowrail.infrastructure.persistence.entity.PasswordResetTokenEntity;

public interface JpaPasswordResetTokenRepository
        extends JpaRepository<PasswordResetTokenEntity, UUID> {

    /** Row-locked lookup so concurrent reset calls serialize on the token row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    List<PasswordResetTokenEntity> findByUserIdAndUsedAtIsNull(UUID userId);

    Optional<PasswordResetTokenEntity> findTopByUserIdOrderByCreatedAtDesc(UUID userId);
}
