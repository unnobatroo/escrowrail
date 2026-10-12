package com.wise.escrow.infrastructure.web.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Registration payload: credentials plus the business entity details. */
public record RegisterUserRequest(
    @NotBlank @Email String email,
    @NotBlank String password,
    @NotBlank String legalName,
    @NotBlank String uboTaxId,
    @NotBlank String country
) {}
