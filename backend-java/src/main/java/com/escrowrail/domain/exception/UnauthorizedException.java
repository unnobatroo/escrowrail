package com.escrowrail.domain.exception;

public class UnauthorizedException extends DomainException {

    /** Thrown when authentication is missing or invalid. */
    public UnauthorizedException(String message) {
        super("ERR_UNAUTHORIZED", 401, message);
    }
}
