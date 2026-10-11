package com.wise.escrow.domain.exception;

public class NotFoundException extends DomainException {

    public NotFoundException(String message) {
        super("ERR_NOT_FOUND", 404, message);
    }
}
