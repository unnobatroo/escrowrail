package com.escrowrail.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import com.escrowrail.infrastructure.persistence.entity.EmailVerificationTokenEntity;

public interface JpaEmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationTokenEntity, UUID> {

    /** Row-locked lookup so concurrent verify calls serialize on the token row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    Optional<EmailVerificationTokenEntity> findByTokenHash(String tokenHash);

    List<EmailVerificationTokenEntity> findByUserIdAndUsedAtIsNull(UUID userId);

    Optional<EmailVerificationTokenEntity> findTopByUserIdOrderByCreatedAtDesc(UUID userId);
}
