package com.wise.escrow.infrastructure.web.dto.auth;

/** Login rejection for unverified accounts — carries the email for resend UX. */
public record EmailNotVerifiedResponse(
    String code,
    String message,
    String email
) {}
