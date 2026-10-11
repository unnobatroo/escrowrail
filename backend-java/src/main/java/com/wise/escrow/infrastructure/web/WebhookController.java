package com.wise.escrow.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wise.escrow.application.dto.ApiDtos.WebhookResult;
import com.wise.escrow.application.service.EscrowService;
import com.wise.escrow.domain.exception.WebhookValidationException;
import com.wise.escrow.domain.model.EscrowContract;
import com.wise.escrow.domain.model.TrackingType;
import com.wise.escrow.domain.repository.EscrowContractRepository;
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

/**
 * Programmatic settlement webhooks. Funds only move FUNDED -> SETTLED
 * through these verified third-party events.
 */
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

    /** Creates a new WebhookController. */
    public WebhookController(
            EscrowService service,
            EscrowContractRepository contracts,
            ObjectMapper mapper,
            @Value("${wise.webhooks.github-secret:}") String githubSecret,
            @Value("${wise.webhooks.logistics-secret:}") String logisticsSecret) {
        this.service = service;
        this.contracts = contracts;
        this.mapper = mapper;
        this.githubSecret = githubSecret;
        this.logisticsSecret = logisticsSecret;
    }

    /**
     * Verifies the HMAC over the raw body and settles funded contracts matching a
     * closed, merged PR's html_url. Other events or unmatched URLs return ignored.
     *
     * @return ignored, or settled with the last successfully settled contract's id
     * @throws WebhookValidationException if unconfigured, the signature is invalid,
     *     or every matched settlement fails
     * @throws java.io.IOException if JSON parsing fails
     * @throws IllegalStateException if HMAC initialization fails
     */
    @PostMapping("/github")
    public WebhookResult github(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature)
            throws java.io.IOException {
        if (githubSecret.isBlank()) {
            throw new WebhookValidationException("GitHub webhook endpoint not configured");
        }
        verifyGithubSignature(body, signature);
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

    /**
     * Verifies the shared secret and accepts DHL deliveries. Non-DELIVERED statuses
     * return ignored before checking the tracking key; delivered events attempt
     * settlement of all matching funded contracts.
     *
     * @return ignored if no delivery or match, otherwise settled with the last successful id
     * @throws WebhookValidationException if unconfigured, the secret or carrier is
     *     invalid, a delivered event lacks a tracking key, or all matched settlements fail
     */
    @PostMapping("/logistics")
    public WebhookResult logistics(
            @RequestBody LogisticsWebhook body,
            @RequestHeader(value = "X-Wise-Webhook-Secret", required = false) String secret) {
        if (logisticsSecret.isBlank()) {
            throw new WebhookValidationException("Logistics webhook endpoint not configured");
        }
        if (secret == null || !constantTimeEquals(secret, logisticsSecret)) {
            throw new WebhookValidationException("Invalid logistics webhook secret");
        }
        if (!"DHL".equals(body.carrier())) {
            throw new WebhookValidationException("Unsupported carrier: " + body.carrier());
        }
        if (!"DELIVERED".equals(body.status())) {
            return new WebhookResult("ignored", null);
        }
        if (body.tracking_key() == null || body.tracking_key().isBlank()) {
            throw new WebhookValidationException("tracking_key is required");
        }
        return settle(TrackingType.SHIPPING_CARRIER, body.tracking_key());
    }

    /**
     * Attempts each matching funded contract in its own transaction. Individual
     * settlement runtime failures are caught so other contracts can still settle.
     *
     * @return ignored if none match, otherwise settled with the last successful id,
     *     including when only some settlements succeed
     * @throws WebhookValidationException if matches exist but every settlement fails
     */
    private WebhookResult settle(TrackingType type, String key) {
        // More than one FUNDED contract can share a tracking key (e.g.
        // two invoices on the same PR); the event settles each of them.
        java.util.List<EscrowContract> funded =
                contracts.findFundedByTracking(type, key);
        if (funded.isEmpty()) {
            return new WebhookResult("ignored", null);
        }
        // Each settlement is its own transaction; a single failure must
        // not block the rest, and redelivery completes the remainder.
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

    private void verifyGithubSignature(byte[] body, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new WebhookValidationException("Missing or malformed GitHub signature");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(githubSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            if (!constantTimeEquals(expected, signature)) {
                throw new WebhookValidationException("Invalid GitHub webhook signature");
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
