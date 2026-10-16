package com.escrowrail.infrastructure.web.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Registration payload: credentials plus the business entity details. */
public record RegisterUserRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, max = 128) String password,
    @NotBlank String legalName,
    @NotBlank String uboTaxId,
    @NotBlank @Size(min = 2, max = 2) String country
) {}
