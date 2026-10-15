package com.escrowrail.infrastructure.mail;

import com.escrowrail.application.port.VerificationEmailSender;
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
                    maskEmail(email));
            return;
        }
        send(email, "Verify your Escrowrail account",
                "✉️", "Verify your email",
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
                    maskEmail(email));
            return;
        }
        send(email, "Reset your Escrowrail password",
                "🔐", "Reset your password",
                "We received a request to reset the password for your Escrowrail"
                        + " account.",
                "Reset password", link,
                "This link expires in 1 hour. If you didn't request a reset,"
                        + " you can ignore this email — your password stays"
                        + " unchanged.");
    }

    /** Emails are PII — keep only the domain in logs. */
    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        return at < 0 ? "***" : "***@" + email.substring(at + 1);
    }

    @Override
    public void sendCounterpartyInvite(String email, String inviterLegalName) {
        if (apiKey.isBlank()) {
            log.error(
                    "escrowrail.mail.resend-api-key unset — invite email to {} dropped; configure the key",
                    maskEmail(email));
            return;
        }
        send(email, inviterLegalName + " invited you to Escrowrail",
                "🤝", inviterLegalName + " needs you",
                inviterLegalName + " opened a conditional escrow trade with you"
                        + " on Escrowrail. Register to see and claim it — the"
                        + " funds only move when your delivery milestone is met.",
                "Create your account", appBaseUrl + "/register",
                "If you weren't expecting this, you can ignore this email.");
    }

    @Override
    public void sendWelcomeEmail(String email, String legalName) {
        if (apiKey.isBlank()) {
            log.error(
                    "escrowrail.mail.resend-api-key unset — welcome email to {} dropped; configure the key",
                    maskEmail(email));
            return;
        }
        send(email, "Welcome to Escrowrail",
                "🎉", "Welcome aboard, " + legalName,
                "Your organization is registered. Open your dashboard to create"
                        + " your first conditional trade.",
                "Open dashboard", appBaseUrl,
                "");
    }

    private void send(
            String email, String subject, String icon, String heading,
            String intro, String cta, String link, String footnote) {
        http.post()
                .uri("https://api.resend.com/emails")
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of(
                        "from", from,
                        "to", new String[]{email},
                        "subject", subject,
                        "html", BrandedEmailTemplate.render(
                                icon, heading, intro, cta, link, footnote,
                                appBaseUrl + "/email-logo.png")))
                .retrieve()
                .toBodilessEntity();
    }
}
