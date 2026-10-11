package com.escrowrail.escrow.domain.exception;

public class ForbiddenException extends DomainException {

    /** Thrown when the caller is authenticated but not permitted. */
    public ForbiddenException(String message) {
        super("ERR_FORBIDDEN", 403, message);
    }
}
