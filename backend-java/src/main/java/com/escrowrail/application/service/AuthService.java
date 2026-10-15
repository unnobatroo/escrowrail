package com.escrowrail.application.service;

import com.escrowrail.application.dto.Events.WelcomeEmailRequested;
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

/**
 * Tenant onboarding. Registration is atomic: organization + user + empty
 * USD/EUR accounts. Balances start at zero; the one-time onboarding deposit
 * via {@link ProfileService} is the only self-service credit.
 */
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

    /**
     * Registers organization + user (normalized email, encoded password).
     * Accounts open at zero — no self-granted balances.
     *
     * @return the saved user and organization
     * @throws DuplicateEmailException if the email exists or the named email constraint fails
     * @throws IllegalArgumentException if the normalized tax ID is empty or too long
     * @throws DataIntegrityViolationException if another database integrity constraint fails
     */
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
            // Trades invited to this email before signup attach now.
            escrow.attachPendingInvites(normalized, org.id());
            issueVerification(user);
            events.publishEvent(
                new WelcomeEmailRequested(user.email(), org.legalName())
            );
            return new Registration(user, org);
        } catch (DataIntegrityViolationException e) {
            // The failed statement aborted the transaction — match the
            // named constraint, rethrow anything else.
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

    /**
     * Authenticates by normalized email + password match.
     *
     * @throws UnauthorizedException if the email is unknown, the password does not
     *     match, or the account is inactive
     */
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

    /**
     * Consumes a verification token and activates the user atomically.
     *
     * @return the now-verified user
     * @throws ValidationException if the token is unknown, expired, or already used
     */
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
        return users.save(user.verified());
    }

    /**
     * Re-issues a verification email. Always appears to succeed (anti-enumeration);
     * 60s cooldown per user.
     */
    @Transactional
    public void resendVerification(String email) {
        String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);
        var user = users.findByEmail(normalized);
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

    /**
     * Issues a reset link. Always appears to succeed (anti-enumeration); 60s
     * cooldown. Outstanding links are consumed — only the newest works.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);
        // Row lock serializes concurrent forgot-password requests past the
        // cooldown check.
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

    /**
     * Consumes a reset token and swaps the password hash atomically.
     *
     * @throws ValidationException if the token is unknown, expired, or already used
     */
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
                            "password reset email send failed for {} — user can re-request",
                            user.email(), e);
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
        // Send after commit — a slow mail call must not hold row locks, and
        // a rollback must not leave a link to a nonexistent account. Plain
        // TransactionSynchronization, not a Modulith event: raw tokens must
        // never be persisted.
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        mailer.sendVerificationEmail(user.email(), rawToken);
                    } catch (RuntimeException e) {
                        log.warn(
                            "verification email send failed for {} — user can resend",
                            user.email(), e);
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
