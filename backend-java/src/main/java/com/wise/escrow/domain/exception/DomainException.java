package com.wise.escrow.domain.exception;

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

    /** Returns the stable machine-readable error code for API responses. */
    public String code() {
        return code;
    }

    /** Returns the HTTP status this exception maps to. */
    public int httpStatus() {
        return httpStatus;
    }
}
