package com.wise.escrow.infrastructure.web.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request to resend the verification email. */
public record ResendVerificationRequest(@NotBlank @Email String email) {}
