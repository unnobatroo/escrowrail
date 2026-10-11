package com.wise.escrow.domain.exception;

public class UnsupportedCurrencyPairException extends DomainException {

    /** Thrown when no FX rate exists for the requested pair. */
    public UnsupportedCurrencyPairException(String message) {
        super("ERR_UNSUPPORTED_CURRENCY_PAIR", 422, message);
    }
}
