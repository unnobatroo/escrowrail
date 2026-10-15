package com.escrowrail.application.event;

/** Integration event; Modulith's registry is the transactional outbox. */
public record CounterpartyInvited(String email, String inviterLegalName) {}
