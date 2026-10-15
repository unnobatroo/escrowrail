package com.escrowrail.infrastructure.persistence.adapter;

import com.escrowrail.domain.model.AppUser;
import com.escrowrail.domain.repository.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.escrowrail.infrastructure.persistence.entity.AppUserEntity;
import com.escrowrail.infrastructure.persistence.jpa.JpaAppUserRepository;

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
    public Optional<AppUser> findByIdForUpdate(UUID id) {
        return jpa.findWithLockById(id).map(AppUserRepositoryAdapter::toDomain);
    }

    @Override
    /** {@inheritDoc} */
    public Optional<AppUser> findByEmailForUpdate(String email) {
        return jpa.findWithLockByEmail(email.strip().toLowerCase(java.util.Locale.ROOT)).map(AppUserRepositoryAdapter::toDomain);
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
