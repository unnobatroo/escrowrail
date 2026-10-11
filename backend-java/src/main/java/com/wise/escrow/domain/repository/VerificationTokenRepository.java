package com.escrowrail.escrow.domain.repository;

import com.escrowrail.escrow.domain.model.VerificationToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VerificationTokenRepository {

    Optional<VerificationToken> findByTokenHash(String tokenHash);

    /** Active (unused) tokens for a user — invalidated on re-issue. */
    List<VerificationToken> findUnusedByUserId(UUID userId);

    /** Newest token for a user — drives the resend cooldown. */
    Optional<VerificationToken> findLatestByUserId(UUID userId);

    VerificationToken save(VerificationToken token);
}
