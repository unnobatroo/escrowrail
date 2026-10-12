package com.wise.escrow.infrastructure.web.dto.escrow;

import java.util.UUID;

/** Webhook outcome: ignored or settled with the contract id. */
public record WebhookResult(String status, UUID contractId) {}
