package com.escrowrail.escrow.infrastructure.web.dto.auth;

/** Issued bearer token response. */
public record AuthTokenResponse(String accessToken, String tokenType) {}
