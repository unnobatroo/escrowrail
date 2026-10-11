package com.wise.escrow.domain.repository;

import com.wise.escrow.domain.model.AppUser;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository {

    Optional<AppUser> findById(UUID id);

    Optional<AppUser> findByEmail(String email);

    AppUser save(AppUser user);
}
