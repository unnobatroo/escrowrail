package com.wise.escrow.domain.exception;

/** Client-supplied input that fails domain validation -> HTTP 400. */
public class ValidationException extends DomainException {

    public ValidationException(String message) {
        super("ERR_VALIDATION", 400, message);
    }
}
