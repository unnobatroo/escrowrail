package com.escrowrail.escrow.domain.exception;

/** Client-supplied input that fails domain validation -> HTTP 400. */
public class ValidationException extends DomainException {

    /** Thrown when an API payload or command fails validation. */
    public ValidationException(String message) {
        super("ERR_VALIDATION", 400, message);
    }
}
