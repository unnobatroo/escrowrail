package com.wise.escrow.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wise.escrow.application.dto.ApiDtos.AccountView;
import com.wise.escrow.application.service.LedgerService;
import com.wise.escrow.domain.exception.NotFoundException;
import com.wise.escrow.domain.exception.ValidationException;
import com.wise.escrow.domain.model.AccountType;
import com.wise.escrow.domain.model.EscrowStatus;
import com.wise.escrow.domain.model.MoneyAmounts;
import com.wise.escrow.domain.model.TrackingType;
import com.wise.escrow.domain.repository.AccountRepository;
import com.wise.escrow.domain.repository.EscrowContractRepository;
import com.wise.escrow.infrastructure.security.TenantPrincipal;
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
 * Sandbox/demo surface: webhook gateway introspection, signed webhook
 * simulation, and balance top-ups. The entire controller exists only under
 * the {@code dev} or {@code demo} profiles — under {@code prod} these
 * routes are unmapped and return HTTP 404.
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
    private final String demoPassword;
    private final boolean allowMockWebhooks;
    private final ViewMapper views;

    /** Creates a new DemoToolsController. */
    public DemoToolsController(
            AccountRepository accounts,
            LedgerService ledgerService,
            EscrowContractRepository contracts,
            WebhookController webhooks,
            ObjectMapper mapper,
            ViewMapper views,
            @Value("${wise.webhooks.github-secret:}") String githubSecret,
            @Value("${wise.webhooks.logistics-secret:}") String logisticsSecret,
            @Value("${wise.demo.buyer-email:buyer@demo.com}") String buyerEmail,
            @Value("${wise.demo.seller-email:seller@demo.com}") String sellerEmail,
            @Value("${wise.seed.password:demo1234}") String demoPassword,
            @Value("${wise.security.allow-mock-webhooks:false}") boolean allowMockWebhooks) {
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
        this.demoPassword = demoPassword;
        this.allowMockWebhooks = allowMockWebhooks;
    }

    /**
     * Gateway introspection for the simulator panel. Exposes the sandbox
     * signing secrets — only safe because the whole controller exists solely
     * in demo mode; with demo disabled these routes do not exist.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "enabled", true,
                "buyer_email", buyerEmail,
                "seller_email", sellerEmail,
                "demo_password", demoPassword,
                "github_webhook_url", "/webhooks/github",
                "logistics_webhook_url", "/webhooks/logistics",
                "github_secret", githubSecret,
                "logistics_secret", logisticsSecret);
    }

    public record TopUpRequest(String currency, BigDecimal amount) {}

    /** Test credit: clearing -> caller's account in the requested currency. */
    @PostMapping("/topup")
    public AccountView topUp(@RequestBody TopUpRequest body) {
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
        var account = accounts
                .findByEntityIdAndTypeAndCurrency(entityId, AccountType.USER, currency)
                .orElseThrow(() -> new NotFoundException("No " + currency + " account"));
        UUID clearing = accounts
                .findSystemAccountId(AccountType.WISE_CLEARING, currency)
                .orElseThrow(() -> new IllegalStateException("Clearing account missing"));
        ledgerService.transfer(
                clearing, account.id(),
                MoneyAmounts.of(body.amount(), currency),
                "Sandbox top-up",
                null);
        return views.toAccountView(
                accounts.findById(account.id()).orElseThrow());
    }

    public record SimulateRequest(UUID contract_id) {}
    public record SimulateResponse(String status, UUID contract_id, String signature) {}

    /**
     * Fires the contract's milestone through the REAL webhook verification
     * path: the payload is HMAC-signed server-side and dispatched to the same
     * handlers third-party webhooks hit. Hard-gated by
     * {@code wise.security.allow-mock-webhooks} in addition to the profile
     * gate — a demo profile deployed without the flag still refuses.
     */
    @PostMapping("/simulate-webhook")
    public SimulateResponse simulate(@RequestBody SimulateRequest body) throws java.io.IOException {
        if (!allowMockWebhooks) {
            throw new NotFoundException("Webhook simulation disabled");
        }
        var contract = contracts
                .findByIdForEntity(body.contract_id(), TenantPrincipal.entityId())
                .orElseThrow(() -> new NotFoundException("Contract not found"));
        if (contract.status() != EscrowStatus.FUNDED) {
            throw new ValidationException("Only FUNDED contracts can be settled");
        }
        if (contract.trackingType() == TrackingType.GITHUB_PR) {
            byte[] payload = mapper.writeValueAsBytes(Map.of(
                    "action", "closed",
                    "pull_request", Map.of(
                            "merged", true,
                            "html_url", contract.trackingKey())));
            var result = webhooks.github(payload, "sha256=" + hmac(payload, githubSecret));
            return new SimulateResponse(result.status(), result.contractId(), "VALID");
        }
        var result = webhooks.logistics(
                new WebhookController.LogisticsWebhook(
                        "DHL", "DELIVERED", contract.trackingKey()),
                logisticsSecret);
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
