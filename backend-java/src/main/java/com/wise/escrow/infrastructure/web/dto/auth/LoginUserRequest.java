package com.escrowrail.escrow.infrastructure.web.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Login credentials. */
public record LoginUserRequest(
    @NotBlank @Email String email,
    @NotBlank String password
) {}
