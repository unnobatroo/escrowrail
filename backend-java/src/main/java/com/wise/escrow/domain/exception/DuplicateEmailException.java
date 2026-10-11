package com.wise.escrow.domain.exception;

public class DuplicateEmailException extends DomainException {

    /** Thrown when registering an email that already exists. */
    public DuplicateEmailException(String message) {
        super("ERR_DUPLICATE_EMAIL", 409, message);
    }
}
