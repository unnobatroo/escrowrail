package com.escrowrail.application.event;

/** Integration event; Modulith's registry is the transactional outbox. */
public record WelcomeEmailRequested(String email, String legalName) {}
