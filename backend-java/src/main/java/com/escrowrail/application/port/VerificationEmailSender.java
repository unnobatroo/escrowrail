package com.escrowrail.application.port;

import java.util.Optional;

/** Transactional verification-email dispatch port. */
public interface VerificationEmailSender {

    /** Sends the verification link embedding {@code rawToken} (never stored raw). */
    void sendVerificationEmail(String email, String rawToken);

    /** Sends the reset link embedding {@code rawToken} (never stored raw). */
    void sendPasswordResetEmail(String email, String rawToken);

    /** Invite to claim a pending escrow trade, sent to an unregistered address. */
    void sendCounterpartyInvite(String email, String inviterLegalName);

    /** Post-registration welcome. */
    void sendWelcomeEmail(String email, String legalName);

    /** Dev/test backdoor: last token issued to an address, kept only by the log sender; prod senders return empty (raw tokens are unrecoverable post-dispatch). */
    default Optional<String> latestTokenFor(String email) {
        return Optional.empty();
    }

    /** Same backdoor for password-reset tokens. */
    default Optional<String> latestResetTokenFor(String email) {
        return Optional.empty();
    }
}
