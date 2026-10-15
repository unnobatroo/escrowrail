package com.escrowrail.domain.exception;

public class CircularOwnershipException extends DomainException {

    /** Thrown when buyer and seller share a UBO tax id. */
    public CircularOwnershipException(String message) {
        super("CIRCULAR_OWNERSHIP_DETECTED", 403, message);
    }
}
