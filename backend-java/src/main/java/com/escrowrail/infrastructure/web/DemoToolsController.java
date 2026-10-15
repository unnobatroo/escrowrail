package com.escrowrail.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.escrowrail.infrastructure.web.dto.ledger.AccountBalanceResponse;
import com.escrowrail.application.service.LedgerService;
import com.escrowrail.domain.exception.NotFoundException;
import com.escrowrail.domain.exception.ValidationException;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.model.EscrowStatus;
import com.escrowrail.domain.model.MoneyAmounts;
import com.escrowrail.domain.model.TrackingType;
import com.escrowrail.domain.repository.AccountRepository;
import com.escrowrail.domain.repository.EscrowContractRepository;
import com.escrowrail.infrastructure.security.TenantPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sandbox surface: webhook introspection, signed simulation, balance
 * top-ups. dev/demo profiles only — unmapped (404) under prod.
 */
@RestController
@RequestMapping("/api/v1/demo")
@Profile({"dev", "demo"})
public class DemoToolsController {

    private static final BigDecimal MAX_TOPUP = new BigDecimal("100000");

    private final AccountRepository accounts;
    private final LedgerService ledgerService;
    private final EscrowContractRepository contracts;
    private final WebhookController webhooks;
    private final ObjectMapper mapper;
    private final String githubSecret;
    private final String logisticsSecret;
    private final String buyerEmail;
    private final String sellerEmail;
    private final com.escrowrail.config.DemoCredentials credentials;
    private final boolean allowMockWebhooks;
    private final ViewMapper views;
    public DemoToolsController(
            AccountRepository accounts,
            LedgerService ledgerService,
            EscrowContractRepository contracts,
            WebhookController webhooks,
            ObjectMapper mapper,
            ViewMapper views,
            @Value("${escrowrail.webhooks.github-secret:}") String githubSecret,
            @Value("${escrowrail.webhooks.logistics-secret:}") String logisticsSecret,
            @Value("${escrowrail.demo.buyer-email:buyer@demo.com}") String buyerEmail,
            @Value("${escrowrail.demo.seller-email:seller@demo.com}") String sellerEmail,
            com.escrowrail.config.DemoCredentials credentials,
            @Value("${escrowrail.security.allow-mock-webhooks:false}") boolean allowMockWebhooks) {
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.contracts = contracts;
        this.webhooks = webhooks;
        this.mapper = mapper;
        this.views = views;
        this.githubSecret = githubSecret;
        this.logisticsSecret = logisticsSecret;
        this.buyerEmail = buyerEmail;
        this.sellerEmail = sellerEmail;
        this.credentials = credentials;
        this.allowMockWebhooks = allowMockWebhooks;
    }

    /**
     * Simulator introspection. Signing secrets never leave the server —
     * simulation is HMAC-signed backend-side via {@code /simulate-webhook}.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "enabled", true,
                "buyer_email", buyerEmail,
                "seller_email", sellerEmail,
                "buyer_password", credentials.buyerPassword(),
                "seller_password", credentials.sellerPassword(),
                "github_webhook_url", "/webhooks/github",
                "logistics_webhook_url", "/webhooks/logistics");
    }

    public record TopUpRequest(String currency, BigDecimal amount) {}

    /** Test credit: clearing -> caller's account. */
    @PostMapping("/topup")
    public AccountBalanceResponse topUp(@RequestBody TopUpRequest body) {
        String currency = body.currency() == null ? "" : body.currency().toUpperCase(java.util.Locale.ROOT);
        if (!currency.equals("USD") && !currency.equals("EUR")) {
            throw new ValidationException("Unsupported currency: " + currency);
        }
        if (body.amount() == null
                || body.amount().compareTo(BigDecimal.ZERO) <= 0
                || body.amount().compareTo(MAX_TOPUP) > 0) {
            throw new ValidationException("Amount must be 0 < x <= " + MAX_TOPUP);
        }
        UUID entityId = TenantPrincipal.entityId();
        // Scalar id lookups: a managed entity load would pin the pre-lock
        // snapshot in the persistence context and lose concurrent updates.
        UUID accountId = accounts
                .findAccountId(entityId, AccountType.USER, currency)
                .orElseThrow(() -> new NotFoundException("No " + currency + " account"));
        UUID clearing = accounts
                .findSystemAccountId(AccountType.ESCROWRAIL_CLEARING, currency)
                .orElseThrow(() -> new IllegalStateException("Clearing account missing"));
        ledgerService.transfer(
                clearing, accountId,
                MoneyAmounts.of(body.amount(), currency),
                "Sandbox top-up",
                null);
        return views.toAccountView(
                accounts.findById(accountId).orElseThrow());
    }

    public record SimulateRequest(UUID contract_id) {}
    public record SimulateResponse(String status, UUID contract_id, String signature) {}

    /**
     * Fires the milestone through the real webhook verification path —
     * HMAC-signed server-side, same handlers as provider webhooks. Also
     * requires {@code escrowrail.security.allow-mock-webhooks}.
     */
    @PostMapping("/simulate-webhook")
    public SimulateResponse simulate(@RequestBody SimulateRequest body) throws java.io.IOException {
        if (!allowMockWebhooks) {
            throw new NotFoundException("Webhook simulation disabled");
        }
        UUID me = TenantPrincipal.entityId();
        var contract = contracts
                .findByIdForEntity(body.contract_id(), me)
                .orElseThrow(() -> new NotFoundException("Contract not found"));
        // Simulated delivery is the buyer's call — the seller must not be
        // able to settle their own payout without a delivery event.
        if (!contract.isBuyer(me)) {
            throw new com.escrowrail.domain.exception.ForbiddenException(
                    "Only the buyer can simulate delivery");
        }
        if (contract.status() != EscrowStatus.FUNDED) {
            throw new ValidationException("Only FUNDED contracts can be settled");
        }
        if (contract.trackingType() == TrackingType.GITHUB_PR) {
            if (githubSecret.isBlank()) {
                throw new ValidationException("GitHub webhook secret not configured");
            }
            byte[] payload = mapper.writeValueAsBytes(Map.of(
                    "action", "closed",
                    "pull_request", Map.of(
                            "merged", true,
                            "html_url", contract.trackingKey())));
            var result = webhooks.github(payload, "sha256=" + hmac(payload, githubSecret));
            return new SimulateResponse(result.status(), result.contractId(), "VALID");
        }
        if (logisticsSecret.isBlank()) {
            throw new ValidationException("Logistics webhook secret not configured");
        }
        byte[] payload = mapper.writeValueAsBytes(new WebhookController.LogisticsWebhook(
                "DHL", "DELIVERED", contract.trackingKey()));
        var result = webhooks.logistics(
                payload, "sha256=" + hmac(payload, logisticsSecret));
        return new SimulateResponse(result.status(), result.contractId(), "VALID");
    }

    private static String hmac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.InvalidKeyException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
