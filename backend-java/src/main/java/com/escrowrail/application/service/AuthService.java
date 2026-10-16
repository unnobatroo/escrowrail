package com.escrowrail.application.service;

import com.escrowrail.application.event.WelcomeEmailRequested;
import com.escrowrail.domain.exception.DuplicateEmailException;
import com.escrowrail.domain.exception.EmailNotVerifiedException;
import com.escrowrail.domain.exception.NotFoundException;
import com.escrowrail.domain.exception.UnauthorizedException;
import com.escrowrail.domain.exception.ValidationException;
import com.escrowrail.domain.model.Account;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.model.AppUser;
import com.escrowrail.domain.model.Organization;
import com.escrowrail.domain.model.PasswordResetToken;
import com.escrowrail.domain.model.VerificationToken;
import com.escrowrail.domain.repository.AccountRepository;
import com.escrowrail.domain.repository.AppUserRepository;
import com.escrowrail.domain.repository.OrganizationRepository;
import com.escrowrail.domain.repository.PasswordResetTokenRepository;
import com.escrowrail.domain.repository.VerificationTokenRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.escrowrail.application.port.VerificationEmailSender;

/** Tenant onboarding. Registration is atomic: org + user + zero-balance USD/EUR accounts; the only self-service credit is the one-time deposit via {@link ProfileService}. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final ApplicationEventPublisher events;
    private final VerificationTokenRepository tokens;
    private final PasswordResetTokenRepository resetTokens;
    private final VerificationEmailSender mailer;
    private final EscrowService escrow;

    public AuthService(
        AppUserRepository users,
        OrganizationRepository organizations,
        AccountRepository accounts,
        PasswordEncoder passwords,
        ApplicationEventPublisher events,
        VerificationTokenRepository tokens,
        PasswordResetTokenRepository resetTokens,
        VerificationEmailSender mailer,
        EscrowService escrow
    ) {
        this.users = users;
        this.organizations = organizations;
        this.accounts = accounts;
        this.passwords = passwords;
        this.events = events;
        this.tokens = tokens;
        this.resetTokens = resetTokens;
        this.mailer = mailer;
        this.escrow = escrow;
    }

    public record Registration(AppUser user, Organization organization) {}

    /** Registers org + user (normalized email, encoded password) and zero-balance accounts. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Registration register(
        String email,
        String password,
        String legalName,
        String uboTaxId,
        String country
    ) {
        String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);
        if (users.findByEmail(normalized).isPresent()) {
            throw new DuplicateEmailException("Email is already registered");
        }
        try {
            Organization org = organizations.save(
                Organization.register(legalName, uboTaxId, country)
            );
            AppUser user = users.save(
                AppUser.register(
                    normalized,
                    passwords.encode(password),
                    org.id()
                )
            );
            accounts.save(
                Account.open(org.id(), AccountType.USER, "USD")
            );
            accounts.save(
                Account.open(org.id(), AccountType.USER, "EUR")
            );
            issueVerification(user);
            events.publishEvent(
                new WelcomeEmailRequested(user.email(), org.legalName())
            );
            return new Registration(user, org);
        } catch (DataIntegrityViolationException e) {
            // The aborted tx leaves only the constraint name to match — rethrow anything else.
            if (
                e.getMessage() != null &&
                e.getMessage().contains("app_users_email_uq")
            ) {
                throw new DuplicateEmailException(
                    "Email is already registered"
                );
            }
            throw e;
        }
    }

    /** Email+password auth; rejects unknown email, bad password, or unverified/inactive accounts. */
    public AppUser authenticate(String email, String password) {
        AppUser user = users
            .findByEmail(email.strip().toLowerCase(java.util.Locale.ROOT))
            .orElseThrow(() ->
                new UnauthorizedException("Invalid email or password")
            );
        if (!passwords.matches(password, user.passwordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        if (!user.emailVerified()) {
            throw new EmailNotVerifiedException(user.email());
        }
        if (!user.active()) {
            throw new UnauthorizedException("Account is deactivated");
        }
        return user;
    }

    /** Consumes a verification token and activates the user; fails on unknown/expired/used tokens. */
    @Transactional
    public AppUser verifyEmail(String rawToken) {
        VerificationToken token = tokens
            .findByTokenHash(sha256(rawToken))
            .filter(VerificationToken::usable)
            .orElseThrow(() ->
                new ValidationException(
                    "Verification link is invalid or expired"
                )
            );
        tokens.save(token.consumed());
        AppUser user = users
            .findById(token.userId())
            .orElseThrow(() -> new NotFoundException("User not found"));
        AppUser verified = users.save(user.verified());
        // Invites attach only after email ownership is proven — earlier, a
        // squatter could claim another party's trades and payouts.
        escrow.attachPendingInvites(verified.email(), verified.entityId());
        return verified;
    }

    /** Re-issues a verification email; always appears to succeed (anti-enumeration), 60s cooldown. */
    @Transactional
    public void resendVerification(String email) {
        String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);
        // Row lock serializes concurrent resends past the cooldown.
        var user = users.findByEmailForUpdate(normalized);
        if (user.isEmpty() || user.get().emailVerified()) {
            return;
        }
        var latest = tokens.findLatestByUserId(user.get().id());
        if (
            latest.isPresent() &&
            latest
                .get()
                .createdAt()
                .isAfter(Instant.now().minus(RESEND_COOLDOWN))
        ) {
            return;
        }
        // Consume outstanding tokens — only the newest link works.
        tokens
            .findUnusedByUserId(user.get().id())
            .forEach(t -> tokens.save(t.consumed()));
        issueVerification(user.get());
    }

    /** Issues a reset link; always appears to succeed (anti-enumeration), 60s cooldown. Older links are consumed. */
    @Transactional
    public void requestPasswordReset(String email) {
        String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);
        // Row lock serializes concurrent requests past the cooldown.
        var user = users.findByEmailForUpdate(normalized);
        if (user.isEmpty() || !user.get().emailVerified()) {
            return;
        }
        var latest = resetTokens.findLatestByUserId(user.get().id());
        if (
            latest.isPresent() &&
            latest
                .get()
                .createdAt()
                .isAfter(Instant.now().minus(RESEND_COOLDOWN))
        ) {
            return;
        }
        resetTokens
            .findUnusedByUserId(user.get().id())
            .forEach(t -> resetTokens.save(t.consumed()));
        issuePasswordReset(user.get());
    }

    /** Consumes a reset token and swaps the password hash; fails on unknown/expired/used tokens. */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = resetTokens
            .findByTokenHash(sha256(rawToken))
            .filter(PasswordResetToken::usable)
            .orElseThrow(() ->
                new ValidationException(
                    "Reset link is invalid or expired"
                )
            );
        resetTokens.save(token.consumed());
        AppUser user = users
            .findById(token.userId())
            .orElseThrow(() -> new NotFoundException("User not found"));
        users.save(user.passwordChanged(passwords.encode(newPassword)));
    }

    /** Generates a 256-bit token, persists its hash, and emails the raw value. */
    private void issuePasswordReset(AppUser user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);
        resetTokens.save(PasswordResetToken.issue(user.id(), sha256(rawToken)));
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        mailer.sendPasswordResetEmail(user.email(), rawToken);
                    } catch (RuntimeException e) {
                        log.warn(
                            "password reset email send failed for user {} — user can re-request",
                            user.id(), e);
                    }
                }
            }
        );
    }

    /** Generates a 256-bit token, persists its hash, and emails the raw value. */
    private void issueVerification(AppUser user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);
        tokens.save(VerificationToken.issue(user.id(), sha256(rawToken)));
        // Send after commit: a slow mail call mustn't hold row locks, and
        // rollback mustn't leave a link to a dead account. Plain
        // TransactionSynchronization — raw tokens are never persisted.
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        mailer.sendVerificationEmail(user.email(), rawToken);
                    } catch (RuntimeException e) {
                        log.warn(
                            "verification email send failed for user {} — user can resend",
                            user.id(), e);
                    }
                }
            }
        );
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                    input.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            );
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
