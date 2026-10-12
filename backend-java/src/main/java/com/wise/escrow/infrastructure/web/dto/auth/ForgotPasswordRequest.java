package com.wise.escrow.infrastructure.web.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Forgot-password request: the address to send a reset link to. */
public record ForgotPasswordRequest(@NotBlank @Email String email) {}
