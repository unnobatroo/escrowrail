package com.escrowrail.escrow.domain.exception;

public class WebhookValidationException extends DomainException {

    /** Thrown when a webhook payload or signature is invalid. */
    public WebhookValidationException(String message) {
        super("ERR_WEBHOOK_VALIDATION", 400, message);
    }
}
