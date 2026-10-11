package com.wise.escrow.domain.exception;

public class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(String message) {
        super("ERR_CURRENCY_MISMATCH", 422, message);
    }
}
