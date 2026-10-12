package com.escrowrail.escrow.application.service;

import java.util.Optional;

/** Transactional verification-email dispatch port. */
public interface VerificationEmailSender {

    /**
     * Sends the activation email containing the verification link that embeds
     * {@code rawToken}. The raw token never leaves this call un-hashed in
     * storage — only the email carries it.
     */
    void sendVerificationEmail(String email, String rawToken);

    /**
     * Sends the password reset email containing the reset link that embeds
     * {@code rawToken}. The raw token never leaves this call un-hashed in
     * storage — only the email carries it.
     */
    void sendPasswordResetEmail(String email, String rawToken);

    /**
     * Dev/test backdoor: the last token issued to an address, when the active
     * sender keeps one (log sender). Production senders return empty — there
     * is no legitimate way to recover a raw token after dispatch.
     */
    default Optional<String> latestTokenFor(String email) {
        return Optional.empty();
    }

    /**
     * Dev/test backdoor for password reset — same contract as
     * {@link #latestTokenFor(String)}.
     */
    default Optional<String> latestResetTokenFor(String email) {
        return Optional.empty();
    }
}
