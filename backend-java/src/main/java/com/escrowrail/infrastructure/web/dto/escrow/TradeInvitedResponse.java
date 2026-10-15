package com.escrowrail.infrastructure.web.dto.escrow;

/** Trade created for an unregistered counterparty — status is INVITED. */
public record TradeInvitedResponse(String status, String message) {}
