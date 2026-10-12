package com.wise.escrow.infrastructure.web.dto.auth;

import com.wise.escrow.infrastructure.web.dto.profile.UserProfileResponse;

/** Registration result carrying the session token and the new profile. */
public record RegisterResponse(String accessToken, UserProfileResponse user) {}
