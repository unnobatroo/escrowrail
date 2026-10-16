package com.escrowrail.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.escrowrail.infrastructure.web.dto.escrow.WebhookResult;
import com.escrowrail.application.service.EscrowService;
import com.escrowrail.domain.exception.WebhookValidationException;
import com.escrowrail.domain.model.EscrowContract;
import com.escrowrail.domain.model.TrackingType;
import com.escrowrail.domain.repository.EscrowContractRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settlement webhooks — the only FUNDED -> SETTLED path. */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WebhookController.class);

    private final EscrowService service;
    private final EscrowContractRepository contracts;
    private final ObjectMapper mapper;
    private final String githubSecret;
    private final String logisticsSecret;
    public WebhookController(
            EscrowService service,
            EscrowContractRepository contracts,
            ObjectMapper mapper,
            @Value("${escrowrail.webhooks.github-secret:}") String githubSecret,
            @Value("${escrowrail.webhooks.logistics-secret:}") String logisticsSecret) {
        this.service = service;
        this.contracts = contracts;
        this.mapper = mapper;
        this.githubSecret = githubSecret;
        this.logisticsSecret = logisticsSecret;
    }

    /** HMAC-verifies the raw body and settles funded contracts matching a merged PR's html_url; anything else → ignored. */
    @PostMapping("/github")
    public WebhookResult github(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature)
            throws java.io.IOException {
        if (githubSecret.isBlank()) {
            throw new WebhookValidationException("GitHub webhook endpoint not configured");
        }
        verifySignature(body, signature, githubSecret, "GitHub");
        JsonNode payload = mapper.readTree(body);
        JsonNode pr = payload.get("pull_request");
        String trackingKey = null;
        boolean merged = false;
        if (pr != null && pr.isObject()) {
            JsonNode url = pr.get("html_url");
            trackingKey = url == null ? null : url.asText();
            merged = pr.path("merged").asBoolean(false);
        }
        if (!"closed".equals(payload.path("action").asText()) || !merged || trackingKey == null) {
            return new WebhookResult("ignored", null);
        }
        return settle(TrackingType.GITHUB_PR, trackingKey);
    }

    public record LogisticsWebhook(String carrier, String status, String tracking_key) {}

    /** HMAC-verified carrier webhooks; DELIVERED settles all matching funded contracts, else ignored. */
    @PostMapping("/logistics")
    public WebhookResult logistics(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Escrowrail-Signature", required = false) String signature)
            throws java.io.IOException {
        if (logisticsSecret.isBlank()) {
            throw new WebhookValidationException("Logistics webhook endpoint not configured");
        }
        verifySignature(body, signature, logisticsSecret, "logistics");
        LogisticsWebhook payload = mapper.readValue(body, LogisticsWebhook.class);
        if (!"DHL".equals(payload.carrier())) {
            throw new WebhookValidationException("Unsupported carrier: " + payload.carrier());
        }
        if (!"DELIVERED".equals(payload.status())) {
            return new WebhookResult("ignored", null);
        }
        if (payload.tracking_key() == null || payload.tracking_key().isBlank()) {
            throw new WebhookValidationException("tracking_key is required");
        }
        return settle(TrackingType.SHIPPING_CARRIER, payload.tracking_key());
    }

    /** Settles each match in its own tx — one failure must not block the rest; fails only if all fail. */
    private WebhookResult settle(TrackingType type, String key) {
        // Multiple FUNDED contracts can share a tracking key.
        java.util.List<EscrowContract> funded =
                contracts.findFundedByTracking(type, key);
        if (funded.isEmpty()) {
            return new WebhookResult("ignored", null);
        }
        // Per-contract transactions; redelivery completes the remainder.
        EscrowContract last = null;
        int settled = 0;
        for (EscrowContract c : funded) {
            try {
                last = service.settleMilestone(c.id());
                settled++;
            } catch (RuntimeException e) {
                log.warn("webhook settle failed contract={} reason={}", c.id(), e.getMessage());
            }
        }
        if (settled == 0) {
            throw new WebhookValidationException("Settlement failed for all matched contracts");
        }
        return new WebhookResult("settled", last.id());
    }

    /** HMAC-SHA256 over the raw body — the signature covers the exact payload. */
    private void verifySignature(byte[] body, String signature, String secret, String label) {
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new WebhookValidationException("Missing or malformed " + label + " signature");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            if (!constantTimeEquals(expected, signature)) {
                throw new WebhookValidationException("Invalid " + label + " webhook signature");
            }
        } catch (java.security.InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
