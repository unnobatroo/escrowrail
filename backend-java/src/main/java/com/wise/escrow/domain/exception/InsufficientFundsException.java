package com.escrowrail.escrow.domain.exception;

public class InsufficientFundsException extends DomainException {

    /** Thrown when a debit would overdraw a non-clearing account. */
    public InsufficientFundsException(String message) {
        super("ERR_INSUFFICIENT_FUNDS", 402, message);
    }
}
