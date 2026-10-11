package com.wise.escrow.domain.exception;

/** Login attempt on an account that has not completed email verification. */
public class EmailNotVerifiedException extends DomainException {

    private final String email;

    public EmailNotVerifiedException(String email) {
        super("EMAIL_NOT_VERIFIED", 403,
                "Account requires email verification before access.");
        this.email = email;
    }

    /** Returns the unverified account's email for the error payload. */
    public String email() {
        return email;
    }
}
