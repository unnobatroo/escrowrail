package com.wise.escrow.domain.exception;

public class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String message) {
        super("ERR_INVALID_STATE", 409, message);
    }
}
