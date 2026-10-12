package com.wise.escrow.application.service;

import com.wise.escrow.application.dto.Events.WelcomeEmailRequested;
import com.wise.escrow.domain.exception.DuplicateEmailException;
import com.wise.escrow.domain.exception.EmailNotVerifiedException;
import com.wise.escrow.domain.exception.NotFoundException;
import com.wise.escrow.domain.exception.UnauthorizedException;
import com.wise.escrow.domain.exception.ValidationException;
import com.wise.escrow.domain.model.Account;
import com.wise.escrow.domain.model.AccountType;
import com.wise.escrow.domain.model.AppUser;
import com.wise.escrow.domain.model.Organization;
import com.wise.escrow.domain.model.VerificationToken;
import com.wise.escrow.domain.repository.AccountRepository;
import com.wise.escrow.domain.repository.AppUserRepository;
import com.wise.escrow.domain.repository.OrganizationRepository;
import com.wise.escrow.domain.repository.VerificationTokenRepository;
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
 * Tenant onboarding. Registration is one transaction: organization + user
 * + empty USD/EUR accounts — no half-registered users. Balances start at
 * zero; the one-time onboarding deposit via {@link ProfileService} is the
 * only self-service credit.
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
    private final VerificationEmailSender mailer;
    private final EscrowService escrow;

    /** Creates a new AuthService. */
    public AuthService(
        AppUserRepository users,
        OrganizationRepository organizations,
        AccountRepository accounts,
        PasswordEncoder passwords,
        ApplicationEventPublisher events,
        VerificationTokenRepository tokens,
        VerificationEmailSender mailer,
        EscrowService escrow
    ) {
        this.users = users;
        this.organizations = organizations;
        this.accounts = accounts;
        this.passwords = passwords;
        this.events = events;
        this.tokens = tokens;
        this.mailer = mailer;
        this.escrow = escrow;
    }

    public record Registration(AppUser user, Organization organization) {}

    /**
     * Registers an organization and active user with a trimmed, lowercase email and
     * encoded password. Accounts open at zero — granting free balances here was
     * the test-account loophole this service no longer carries.
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
            // Any trades invited to this email before signup attach now —
            // the new entity steps into the open seller slot.
            escrow.attachPendingInvites(normalized, org.id());
            issueVerification(user);
            events.publishEvent(
                new WelcomeEmailRequested(user.email(), org.legalName())
            );
            return new Registration(user, org);
        } catch (DataIntegrityViolationException e) {
            // On PostgreSQL the failed statement already aborted the
            // transaction — no re-query is possible here, so match on the
            // named constraint and rethrow everything else.
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
     * Returns the active user whose encoded password matches, normalizing the email
     * by stripping surrounding whitespace and lowercasing it.
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
     * Consumes a verification token: marks it used and activates the user in
     * one transaction so a token can never activate twice.
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
     * Re-issues a verification email. Always succeeds from the caller's point
     * of view (unknown addresses are silently dropped to prevent enumeration);
     * a 60-second cooldown per user suppresses rapid re-sends.
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
        // Invalidate outstanding links before issuing the new one — only the
        // newest token may activate the account.
        tokens
            .findUnusedByUserId(user.get().id())
            .forEach(t -> tokens.save(t.consumed()));
        issueVerification(user.get());
    }

    /** Generates a 256-bit token, persists its hash, and emails the raw value. */
    private void issueVerification(AppUser user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);
        tokens.save(VerificationToken.issue(user.id(), sha256(rawToken)));
        // Send after commit: a slow/failed mail call must not hold the
        // registration transaction's row locks, and a rollback must not
        // leave the user holding a link to an account that does not exist.
        // A plain TransactionSynchronization (not a @TransactionalEventListener)
        // is used on purpose — Modulith persists event payloads to
        // event_publication, and the raw token must never be stored unhashed.
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
