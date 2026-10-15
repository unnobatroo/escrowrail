package com.escrowrail.infrastructure.web.dto.auth;

/** Registration landed but the account activates only via the emailed link. */
public record PendingVerificationResponse(String status, String email) {}
