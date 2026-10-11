package com.wise.escrow.domain.exception;

public class InvalidStateTransitionException extends DomainException {

    /** Thrown when a contract transition violates the state machine. */
    public InvalidStateTransitionException(String message) {
        super("ERR_INVALID_STATE", 409, message);
    }
}
