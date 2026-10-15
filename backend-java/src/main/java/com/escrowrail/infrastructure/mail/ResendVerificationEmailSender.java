package com.escrowrail.infrastructure.mail;

import com.escrowrail.application.service.VerificationEmailSender;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resend sender for production. Without {@code escrowrail.mail.resend-api-key}
 * the send degrades to a log line rather than silently dropping activations.
 */
@Component
@Profile({"prod", "demo"})
class ResendVerificationEmailSender implements VerificationEmailSender {

    private static final Logger log =
            LoggerFactory.getLogger(ResendVerificationEmailSender.class);

    private final RestClient http;
    private final String apiKey;
    private final String from;
    private final String appBaseUrl;

    ResendVerificationEmailSender(
            @Value("${escrowrail.mail.resend-api-key:}") String apiKey,
            @Value("${escrowrail.mail.from:Escrowrail <escrow@escrowrail-demo.app>}") String from,
            @Value("${escrowrail.app-base-url:https://escrowrail.vercel.app}") String appBaseUrl) {
        this.apiKey = apiKey;
        this.from = from;
        this.appBaseUrl = appBaseUrl;
        var httpFactory = new SimpleClientHttpRequestFactory();
        httpFactory.setConnectTimeout(Duration.ofSeconds(5));
        httpFactory.setReadTimeout(Duration.ofSeconds(10));
        this.http =
            RestClient.builder().requestFactory(httpFactory).build();
    }

    @Override
    public void sendVerificationEmail(String email, String rawToken) {
        String link = appBaseUrl + "/verify-email?token=" + rawToken;
        if (apiKey.isBlank()) {
            // Never log the link — it embeds the raw token. Fail loudly.
            log.error(
                    "escrowrail.mail.resend-api-key unset — verification email to {} dropped; configure the key",
                    email);
            return;
        }
        send(email, "Verify your Escrowrail account",
                "Verify your email",
                "You're almost in. Confirm this email address to activate your"
                        + " Escrowrail account.",
                "Verify email address", link,
                "This link expires in 24 hours. If you didn't create an"
                        + " account, you can ignore this email.");
    }

    @Override
    public void sendPasswordResetEmail(String email, String rawToken) {
        String link = appBaseUrl + "/reset-password?token=" + rawToken;
        if (apiKey.isBlank()) {
            log.error(
                    "escrowrail.mail.resend-api-key unset — password reset email to {} dropped; configure the key",
                    email);
            return;
        }
        send(email, "Reset your Escrowrail password",
                "Reset your password",
                "We received a request to reset the password for your Escrowrail"
                        + " account.",
                "Reset password", link,
                "This link expires in 1 hour. If you didn't request a reset,"
                        + " you can ignore this email — your password stays"
                        + " unchanged.");
    }

    private void send(
            String email, String subject, String heading, String intro,
            String cta, String link, String footnote) {
        http.post()
                .uri("https://api.resend.com/emails")
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of(
                        "from", from,
                        "to", new String[]{email},
                        "subject", subject,
                        "html", BrandedEmailTemplate.render(heading, intro, cta, link, footnote)))
                .retrieve()
                .toBodilessEntity();
    }
}
