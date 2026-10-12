package com.wise.escrow.infrastructure.mail;

import com.wise.escrow.application.service.VerificationEmailSender;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Local/test sender: logs the activation link and retains the last token per
 * address so the demo gateway and E2E tests can complete verification without
 * an external mail provider.
 */
@Component
@Profile({"dev", "test"})
@Primary
class LoggingVerificationEmailSender implements VerificationEmailSender {

    private static final Logger log =
            LoggerFactory.getLogger(LoggingVerificationEmailSender.class);

    private final String appBaseUrl;
    private final Map<String, String> lastTokens = new ConcurrentHashMap<>();
    private final Map<String, String> lastResetTokens = new ConcurrentHashMap<>();

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
    public void sendPasswordResetEmail(String email, String rawToken) {
        lastResetTokens.put(email, rawToken);
        log.info(
                "password reset email (dev outbox) to={} link={}/reset-password?token={}",
                email, appBaseUrl, rawToken);
    }

    @Override
    public Optional<String> latestTokenFor(String email) {
        return Optional.ofNullable(lastTokens.get(email));
    }

    @Override
    public Optional<String> latestResetTokenFor(String email) {
        return Optional.ofNullable(lastResetTokens.get(email));
    }
}

/**
 * Production sender via the Resend transactional API. When
 * {@code wise.mail.resend-api-key} is unset the send degrades to a log line —
 * the token is never exposed outside the intended channel in a real deploy,
 * but a misconfigured env must not silently drop activations.
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
            @Value("${wise.mail.resend-api-key:}") String apiKey,
            @Value("${wise.mail.from:Wise Smart Escrow <escrow@wise-demo.app>}") String from,
            @Value("${wise.app-base-url:https://wise-escrow.vercel.app}") String appBaseUrl) {
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
            // Never log the link — it embeds the raw token and anyone with
            // log access could activate the account. Fail loudly instead.
            log.error(
                    "wise.mail.resend-api-key unset — verification email to {} dropped; configure the key",
                    email);
            return;
        }
        send(email, "Verify your Wise Smart Escrow account",
                "Verify your email",
                "You're almost in. Confirm this email address to activate your"
                        + " Wise Smart Escrow account.",
                "Verify email address", link,
                "This link expires in 24 hours. If you didn't create an"
                        + " account, you can ignore this email.");
    }

    @Override
    public void sendPasswordResetEmail(String email, String rawToken) {
        String link = appBaseUrl + "/reset-password?token=" + rawToken;
        if (apiKey.isBlank()) {
            log.error(
                    "wise.mail.resend-api-key unset — password reset email to {} dropped; configure the key",
                    email);
            return;
        }
        send(email, "Reset your Wise Smart Escrow password",
                "Reset your password",
                "We received a request to reset the password for your Wise"
                        + " Smart Escrow account.",
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
                        "html", wiseTemplate(heading, intro, cta, link, footnote)))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Neptune-styled transactional template: forest-green wordmark, charcoal
     * body, neon CTA on the warm-sand canvas — mirrors the product UI tokens.
     * Table layout + inline styles for email-client compatibility.
     */
    private static String wiseTemplate(
            String heading, String intro, String cta, String link, String footnote) {
        return """
                <!DOCTYPE html>
                <html><body style="margin:0;padding:0;background-color:#F4F5F0;font-family:Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#F4F5F0;padding:32px 16px;">
                <tr><td align="center">
                <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="max-width:480px;width:100%%;background-color:#FFFFFF;border-radius:16px;border:1px solid #E8EAE3;padding:40px 32px;">
                  <tr><td>
                    <div style="font-size:20px;font-weight:700;color:#163300;letter-spacing:-0.02em;">Wise <span style="color:#9FE870;">◆</span> Smart Escrow</div>
                    <h1 style="margin:24px 0 8px;font-size:24px;font-weight:700;color:#163300;line-height:1.25;">%s</h1>
                    <p style="margin:0 0 24px;font-size:15px;line-height:1.6;color:#1B1C1D;">%s</p>
                    <table role="presentation" cellpadding="0" cellspacing="0"><tr><td style="border-radius:999px;background-color:#9FE870;">
                      <a href="%s" style="display:inline-block;padding:14px 32px;font-size:15px;font-weight:700;color:#163300;text-decoration:none;border-radius:999px;">%s</a>
                    </td></tr></table>
                    <p style="margin:24px 0 0;font-size:13px;line-height:1.5;color:#5C685B;">%s</p>
                    <p style="margin:16px 0 0;font-size:12px;line-height:1.5;color:#88908A;word-break:break-all;">Button not working? Paste this into your browser:<br><a href="%s" style="color:#163300;">%s</a></p>
                  </td></tr>
                </table>
                <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="max-width:480px;width:100%%;"><tr><td style="padding:16px 8px;font-size:11px;color:#88908A;text-align:center;">
                  Conditional B2B settlement at the mid-market rate — funds held in segregated EMI vaults.
                </td></tr></table>
                </td></tr></table>
                </body></html>
                """
                .formatted(heading, intro, link, cta, footnote, link, link);
    }
}
