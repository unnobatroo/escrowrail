package com.wise.escrow.application.dto;

import java.util.UUID;

/** Integration events published inside the ledger transaction; Spring
 * Modulith's event-publication registry provides the transactional outbox. */
public final class Events {

    private Events() {}

    public record ContractFunded(UUID contractId, UUID buyerId, UUID sellerId) {}

    public record ContractSettled(UUID contractId, UUID buyerId, UUID sellerId) {}

    public record ContractCancelled(UUID contractId, UUID buyerId, UUID sellerId) {}

    public record CounterpartyInvited(String email, String inviterLegalName) {}

    public record WelcomeEmailRequested(String email, String legalName) {}
}
