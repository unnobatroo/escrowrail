package com.wise.escrow.domain.exception;

public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException(String message) {
        super("ERR_INSUFFICIENT_FUNDS", 402, message);
    }
}
