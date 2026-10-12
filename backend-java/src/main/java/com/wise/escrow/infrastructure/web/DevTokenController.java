package com.wise.escrow.infrastructure.web;

import com.wise.escrow.application.service.VerificationEmailSender;
import com.wise.escrow.domain.exception.NotFoundException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E backdoor: exposes the last verification token the dev log-mailer
 * issued for an address, so automated tests can complete the verification
 * flow without a mailbox. Strictly a {@code dev}/{@code test} artifact —
 * under {@code prod} (and the demo deploy profile) this route is unmapped.
 */
@RestController
@RequestMapping("/dev")
@Profile({"dev", "test"})
public class DevTokenController {

    private final VerificationEmailSender mailer;

    /** Creates a new DevTokenController. */
    public DevTokenController(VerificationEmailSender mailer) {
        this.mailer = mailer;
    }

    @GetMapping("/verification-token")
    /** Latest issued token for an email, if the active sender retains one. */
    public Map<String, String> latest(@RequestParam String email) {
        String token = mailer
                .latestTokenFor(email.strip().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new NotFoundException(
                        "No verification token issued for this email"));
        return Map.of("token", token);
    }

    @GetMapping("/reset-token")
    /** Latest issued password reset token for an email, if retained. */
    public Map<String, String> latestReset(@RequestParam String email) {
        String token = mailer
                .latestResetTokenFor(email.strip().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new NotFoundException(
                        "No reset token issued for this email"));
        return Map.of("token", token);
    }
}
