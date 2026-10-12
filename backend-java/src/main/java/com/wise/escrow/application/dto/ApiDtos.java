package com.wise.escrow.application.dto;

import com.wise.escrow.domain.model.EscrowStatus;
import com.wise.escrow.domain.model.TrackingType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** API payloads as Java records. */
public final class ApiDtos {

    private ApiDtos() {}

    public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank String legalName,
        @NotBlank String uboTaxId,
        @NotBlank String country
    ) {}

    public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
    ) {}

    public record TokenResponse(String accessToken, String tokenType) {}

    public record EntityView(
        UUID id,
        String legalName,
        String uboTaxId,
        String country
    ) {}

    public record UserView(
        UUID id,
        String email,
        boolean isActive,
        boolean emailVerified,
        String status,
        EntityView entity,
        Instant createdAt
    ) {}

    public record RegisterResponse(String accessToken, UserView user) {}

    /** Registration lands here — the account only activates via the emailed link. */
    public record PendingVerificationView(String status, String email) {}

    public record VerifyEmailRequest(@NotBlank String token) {}

    public record ResendVerificationRequest(@NotBlank @Email String email) {}

    /** Login rejection for unverified accounts — carries the email for resend UX. */
    public record EmailNotVerifiedView(String code, String message, String email) {}

    public record AccountView(
        UUID id,
        UUID entityId,
        String accountType,
        String currency,
        BigDecimal balance
    ) {}

    public record CreateContractRequest(
        @NotBlank @Email String counterpartyEmail,
        @NotNull @DecimalMin("0.01") BigDecimal targetAmount,
        String targetCurrency,
        String sourceCurrency,
        @NotNull TrackingType trackingType,
        @NotBlank String trackingKey
    ) {}

    public record FundRequest(
        @NotNull @DecimalMin("0.01") BigDecimal depositAmount
    ) {}

    public record ContractView(
        UUID id,
        UUID buyerId,
        UUID sellerId,
        String buyerName,
        String sellerName,
        String role,
        EscrowStatus status,
        String targetCurrency,
        BigDecimal targetAmount,
        String sourceCurrency,
        BigDecimal depositAmount,
        BigDecimal lockedSpotRate,
        BigDecimal escrowedAmount,
        TrackingType trackingType,
        String trackingKey,
        Instant fundedAt,
        Instant createdAt
    ) {}

    public record CounterpartyView(
        String email,
        UUID entityId,
        String legalName,
        String country,
        boolean sameUbo
    ) {}

    public record QuoteView(
        String sourceCurrency,
        String targetCurrency,
        BigDecimal targetAmount,
        BigDecimal midMarketRate,
        BigDecimal principal,
        BigDecimal wiseFee,
        BigDecimal totalDeposit,
        BigDecimal bankSpreadCost,
        BigDecimal bankSwiftFee,
        BigDecimal bankTotalCost,
        BigDecimal customerSavings
    ) {}

    public record LedgerEntryView(
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
        public static LedgerEntryView of(com.wise.escrow.domain.repository.LedgerRepository.LedgerEntry e) {
            return new LedgerEntryView(
                    e.id(), e.debitAccountId(), e.creditAccountId(),
                    e.amount(), e.currency(), e.description(), e.contractId(),
                    classify(e.description()), e.currentHash(), e.createdAt());
        }

        /**
         * Buckets free-text ledger descriptions into stable operation types for
         * the activity feed. Descriptions are the only taxonomy on the row, so
         * this is presentation-layer classification, not a persisted enum.
         */
        private static String classify(String description) {
            String d = description.toLowerCase(java.util.Locale.ROOT);
            if (d.contains("sandbox")) return "TOP_UP";
            if (d.contains("fee")) return "FEE_DEDUCTION";
            if (d.contains("vault funding")) return "VAULT_LOCK";
            if (d.contains("payout")) return "ESCROW_PAYOUT";
            if (d.contains("liquidation")) return "VAULT_LIQUIDATION";
            if (d.contains("refund")) return "REFUND";
            if (d.contains("fx")) return "FX_CONVERSION";
            return "TRANSFER";
        }
    }

    public record WebhookResult(String status, UUID contractId) {}

    public record InvitedResponse(String status, String message) {}

    public record ErrorView(String code, String message) {}
}
