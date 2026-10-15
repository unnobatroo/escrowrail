package com.escrowrail.application.service;

import java.util.Optional;

/** Transactional verification-email dispatch port. */
public interface VerificationEmailSender {

    /** Sends the verification link embedding {@code rawToken} (never stored raw). */
    void sendVerificationEmail(String email, String rawToken);

    /** Sends the reset link embedding {@code rawToken} (never stored raw). */
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
