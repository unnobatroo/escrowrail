package com.escrowrail.infrastructure.persistence;

import com.escrowrail.domain.model.PasswordResetToken;
import com.escrowrail.domain.repository.PasswordResetTokenRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PasswordResetTokenRepositoryAdapter implements PasswordResetTokenRepository {

    private final JpaPasswordResetTokenRepository jpa;

    PasswordResetTokenRepositoryAdapter(JpaPasswordResetTokenRepository jpa) {
        this.jpa = jpa;
    }

    static PasswordResetToken toDomain(PasswordResetTokenEntity e) {
        return new PasswordResetToken(
                e.getId(), e.getUserId(), e.getTokenHash(),
                e.getExpiresAt(), e.getUsedAt(), e.getCreatedAt());
    }

    @Override
    /** {@inheritDoc} */
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(PasswordResetTokenRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public List<PasswordResetToken> findUnusedByUserId(UUID userId) {
        return jpa.findByUserIdAndUsedAtIsNull(userId).stream()
                .map(PasswordResetTokenRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    /** {@inheritDoc} */
    public Optional<PasswordResetToken> findLatestByUserId(UUID userId) {
        return jpa.findTopByUserIdOrderByCreatedAtDesc(userId)
                .map(PasswordResetTokenRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public PasswordResetToken save(PasswordResetToken token) {
        return toDomain(jpa.save(new PasswordResetTokenEntity(
                token.id(), token.userId(), token.tokenHash(),
                token.expiresAt(), token.usedAt(), token.createdAt())));
    }
}
