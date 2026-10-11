package com.escrowrail.escrow.infrastructure.web;

import com.escrowrail.escrow.application.service.VerificationEmailSender;
import com.escrowrail.escrow.domain.exception.NotFoundException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E backdoor: exposes the last verification token the dev log-mailer
 * issued for an address, so automated tests can complete the verification
 * flow without a mailbox. Exists only when {@code escrowrail.demo.enabled} — the
 * production sender never retains raw tokens.
 */
@RestController
@RequestMapping("/dev")
@ConditionalOnProperty(name = "escrowrail.demo.enabled", havingValue = "true")
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
}
