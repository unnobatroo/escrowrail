package com.wise.escrow.infrastructure.mail;

import com.wise.escrow.application.service.VerificationEmailSender;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Local/test sender: logs the activation link and retains the last token per
 * address so the demo gateway and E2E tests can complete verification without
 * an external mail provider.
 */
@Component
@Profile("!prod")
class LoggingVerificationEmailSender implements VerificationEmailSender {

    private static final Logger log =
            LoggerFactory.getLogger(LoggingVerificationEmailSender.class);

    private final String appBaseUrl;
    private final Map<String, String> lastTokens = new ConcurrentHashMap<>();

    LoggingVerificationEmailSender(
            @Value("${wise.app-base-url:http://localhost:5173}") String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    @Override
    public void sendVerificationEmail(String email, String rawToken) {
        lastTokens.put(email, rawToken);
        log.info(
                "verification email (dev outbox) to={} link={}/verify-email?token={}",
                email, appBaseUrl, rawToken);
    }

    @Override
    public Optional<String> latestTokenFor(String email) {
        return Optional.ofNullable(lastTokens.get(email));
    }
}

/**
 * Production sender via the Resend transactional API. When
 * {@code wise.mail.resend-api-key} is unset the send degrades to a log line —
 * the token is never exposed outside the intended channel in a real deploy,
 * but a misconfigured env must not silently drop activations.
 */
@Component
@Profile("prod")
class ResendVerificationEmailSender implements VerificationEmailSender {

    private static final Logger log =
            LoggerFactory.getLogger(ResendVerificationEmailSender.class);

    private final RestClient http;
    private final String apiKey;
    private final String from;
    private final String appBaseUrl;

    ResendVerificationEmailSender(
            @Value("${wise.mail.resend-api-key:}") String apiKey,
            @Value("${wise.mail.from:Wise Smart Escrow <escrow@wise-demo.app>}") String from,
            @Value("${wise.app-base-url:https://wise-escrow.vercel.app}") String appBaseUrl) {
        this.apiKey = apiKey;
        this.from = from;
        this.appBaseUrl = appBaseUrl;
        this.http = RestClient.create();
    }

    @Override
    public void sendVerificationEmail(String email, String rawToken) {
        String link = appBaseUrl + "/verify-email?token=" + rawToken;
        if (apiKey.isBlank()) {
            log.warn(
                    "wise.mail.resend-api-key unset — verification link for {} logged instead of sent",
                    email);
            log.info("verification link to={} link={}", email, link);
            return;
        }
        http.post()
                .uri("https://api.resend.com/emails")
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of(
                        "from", from,
                        "to", new String[]{email},
                        "subject", "Verify your Wise Smart Escrow account",
                        "html", "<p>Activate your account:</p><p><a href=\"" + link
                                + "\">Verify email address</a></p>"))
                .retrieve()
                .toBodilessEntity();
    }
}
