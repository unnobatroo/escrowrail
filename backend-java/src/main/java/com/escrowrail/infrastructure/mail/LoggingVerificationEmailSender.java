package com.escrowrail.infrastructure.mail;

import com.escrowrail.application.service.VerificationEmailSender;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local/test sender: logs the link and retains the last token per address
 * so the demo gateway and E2E can verify without a mail provider.
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
            @Value("${escrowrail.app-base-url:http://localhost:5173}") String appBaseUrl) {
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
