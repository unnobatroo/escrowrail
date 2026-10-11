package com.wise.escrow.domain.exception;

public class CircularOwnershipException extends DomainException {

    public CircularOwnershipException(String message) {
        super("CIRCULAR_OWNERSHIP_DETECTED", 403, message);
    }
}
