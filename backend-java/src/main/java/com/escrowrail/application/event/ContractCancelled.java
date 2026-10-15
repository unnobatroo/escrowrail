package com.escrowrail.application.event;

import java.util.UUID;

/** Integration event; Modulith's registry is the transactional outbox. */
public record ContractCancelled(UUID contractId, UUID buyerId, UUID sellerId) {}
