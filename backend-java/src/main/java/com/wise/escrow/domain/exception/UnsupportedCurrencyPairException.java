package com.wise.escrow.domain.exception;

public class UnsupportedCurrencyPairException extends DomainException {

    public UnsupportedCurrencyPairException(String message) {
        super("ERR_UNSUPPORTED_CURRENCY_PAIR", 422, message);
    }
}
