package com.escrowrail.escrow.domain.exception;

public class CurrencyMismatchException extends DomainException {

    /** Thrown when money arithmetic mixes currencies. */
    public CurrencyMismatchException(String message) {
        super("ERR_CURRENCY_MISMATCH", 422, message);
    }
}
