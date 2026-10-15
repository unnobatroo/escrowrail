package com.escrowrail.domain.exception;

public class NotFoundException extends DomainException {

    /** Thrown when a requested aggregate does not exist (or is not visible to the tenant). */
    public NotFoundException(String message) {
        super("ERR_NOT_FOUND", 404, message);
    }
}
