package com.escrowrail.infrastructure.web.dto.auth;

import com.escrowrail.infrastructure.web.dto.profile.UserProfileResponse;

/** Registration result carrying the session token and the new profile. */
public record RegisterResponse(String accessToken, UserProfileResponse user) {}
