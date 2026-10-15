package com.escrowrail.infrastructure.persistence.adapter;

import com.escrowrail.domain.model.VerificationToken;
import com.escrowrail.domain.repository.VerificationTokenRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.escrowrail.infrastructure.persistence.entity.EmailVerificationTokenEntity;
import com.escrowrail.infrastructure.persistence.jpa.JpaEmailVerificationTokenRepository;

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
