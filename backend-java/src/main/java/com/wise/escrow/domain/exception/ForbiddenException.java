package com.wise.escrow.domain.exception;

public class ForbiddenException extends DomainException {

    public ForbiddenException(String message) {
        super("ERR_FORBIDDEN", 403, message);
    }
}
