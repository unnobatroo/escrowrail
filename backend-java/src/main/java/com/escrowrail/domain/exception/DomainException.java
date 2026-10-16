package com.escrowrail.domain.exception;

/** Base for all typed domain exceptions; carries HTTP semantics upward. */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    /** JPA / reflective use only. */
    protected DomainException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
