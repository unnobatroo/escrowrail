package com.escrowrail.infrastructure.web.dto.profile;

import java.time.Instant;
import java.util.UUID;

/** Authenticated user profile including the tenant entity. */
public record UserProfileResponse(
    UUID id,
    String email,
    boolean isActive,
    boolean emailVerified,
    String status,
    boolean onboardingCompleted,
    EntitySummaryResponse entity,
    Instant createdAt
) {}
