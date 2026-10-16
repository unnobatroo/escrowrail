package com.escrowrail.infrastructure.web.dto.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One double-entry ledger row with its hash-chain link and operation type. */
public record LedgerTransactionResponse(
    UUID id,
    UUID debitAccountId,
    UUID creditAccountId,
    BigDecimal amount,
    String currency,
    String description,
    UUID contractId,
    String txType,
    String currentHash,
    Instant createdAt
) {
    /** Maps a domain entry to its wire view with a classified tx type. */
    public static LedgerTransactionResponse of(
        com.escrowrail.domain.repository.LedgerRepository.LedgerEntry e
    ) {
        return new LedgerTransactionResponse(
            e.id(),
            e.debitAccountId(),
            e.creditAccountId(),
            e.amount(),
            e.currency(),
            e.description(),
            e.contractId(),
            classify(e.description()),
            e.currentHash(),
            e.createdAt()
        );
    }

    /**
     * Buckets free-text ledger descriptions into stable operation types for
     * the activity feed. Descriptions are the only taxonomy on the row, so
     * this is presentation-layer classification, not a persisted enum.
     */
    private static String classify(String description) {
        String d = description.toLowerCase(java.util.Locale.ROOT);
        if (
            d.contains("sandbox") ||
            d.contains("onboarding deposit") ||
            d.contains("opening balance")
        ) {
            return "TOP_UP";
        }
        if (d.contains("fee")) return "FEE_DEDUCTION";
        if (d.contains("vault funding")) return "VAULT_LOCK";
        if (d.contains("payout")) return "ESCROW_PAYOUT";
        if (d.contains("liquidation")) return "VAULT_LIQUIDATION";
        if (d.contains("refund")) return "REFUND";
        if (d.contains("fx") || d.contains("conversion")) return "FX_CONVERSION";
        return "TRANSFER";
    }
}
