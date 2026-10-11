package com.wise.escrow.domain.exception;

public class UnauthorizedException extends DomainException {

    public UnauthorizedException(String message) {
        super("ERR_UNAUTHORIZED", 401, message);
    }
}
