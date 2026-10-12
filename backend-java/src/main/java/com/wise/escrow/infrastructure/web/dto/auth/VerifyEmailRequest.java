package com.escrowrail.escrow.infrastructure.web.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** One-time email verification token submitted by the user. */
public record VerifyEmailRequest(@NotBlank String token) {}
