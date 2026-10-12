package com.escrowrail.escrow.domain.repository;

import com.escrowrail.escrow.domain.model.PasswordResetToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Active (unused) tokens for a user — invalidated on re-issue. */
    List<PasswordResetToken> findUnusedByUserId(UUID userId);

    /** Newest token for a user — drives the request cooldown. */
    Optional<PasswordResetToken> findLatestByUserId(UUID userId);

    PasswordResetToken save(PasswordResetToken token);
}
