package com.wise.escrow.domain.exception;

public class WebhookValidationException extends DomainException {

    public WebhookValidationException(String message) {
        super("ERR_WEBHOOK_VALIDATION", 400, message);
    }
}
