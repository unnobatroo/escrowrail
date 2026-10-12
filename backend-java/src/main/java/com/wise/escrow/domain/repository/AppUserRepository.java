package com.escrowrail.escrow.domain.repository;

import com.escrowrail.escrow.domain.model.AppUser;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository {
    Optional<AppUser> findById(UUID id);

    Optional<AppUser> findByEmail(String email);

    /** {@link #findByEmail} under a pessimistic write lock — serializes
     *  concurrent reset/cooldown checks on the user row. */
    Optional<AppUser> findByEmailForUpdate(String email);

    AppUser save(AppUser user);
}
