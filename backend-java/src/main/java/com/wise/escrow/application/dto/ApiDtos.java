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

    public record EntityView(UUID id, String legalName, String country) {}

    public record UserView(
        UUID id,
        String email,
        EntityView entity,
        Instant createdAt
    ) {}

    public record RegisterResponse(String accessToken, UserView user) {}

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
        Instant createdAt
    ) {}

    public record WebhookResult(String status, UUID contractId) {}

    public record InvitedResponse(String status, String message) {}

    public record ErrorView(String code, String message) {}
}
