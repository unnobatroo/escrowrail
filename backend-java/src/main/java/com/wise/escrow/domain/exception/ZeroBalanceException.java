package com.wise.escrow.domain.exception;

/**
 * Funding attempt from an empty account — distinct from a partial
 * shortfall so the UI can route straight to a top-up affordance.
 */
public class ZeroBalanceException extends DomainException {

    /** Thrown when a 100%-collateralized action starts from a zero balance. */
    public ZeroBalanceException(String message) {
        super("ZERO_BALANCE_LOCKED", 402, message);
    }
}
