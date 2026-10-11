package com.escrowrail.escrow.domain.exception;

/** Base for all typed domain exceptions; carries HTTP semantics upward. */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    /** For JPA / reflective use only. */
    protected DomainException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** C. */
    public String code() {
        return code;
    }

    /** H status. */
    public int httpStatus() {
        return httpStatus;
    }
}
