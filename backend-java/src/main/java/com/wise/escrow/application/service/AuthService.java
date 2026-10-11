package com.escrowrail.escrow.application.service;

import com.escrowrail.escrow.application.dto.Events.WelcomeEmailRequested;
import com.escrowrail.escrow.domain.exception.DuplicateEmailException;
import com.escrowrail.escrow.domain.exception.UnauthorizedException;
import com.escrowrail.escrow.domain.model.Account;
import com.escrowrail.escrow.domain.model.AccountType;
import com.escrowrail.escrow.domain.model.AppUser;
import com.escrowrail.escrow.domain.model.MoneyAmounts;
import com.escrowrail.escrow.domain.model.Organization;
import com.escrowrail.escrow.domain.repository.AccountRepository;
import com.escrowrail.escrow.domain.repository.AppUserRepository;
import com.escrowrail.escrow.domain.repository.OrganizationRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant onboarding. Registration is one transaction: organization + user
 * + USD/EUR accounts funded through the ledger — no half-registered users.
 */
@Service
public class AuthService {

    public static final BigDecimal SANDBOX_USD = new BigDecimal("50000.00");
    public static final BigDecimal SANDBOX_EUR = new BigDecimal("10000.00");

    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final AccountRepository accounts;
    private final LedgerService ledgerService;
    private final PasswordEncoder passwords;
    private final ApplicationEventPublisher events;

    /** Creates a new AuthService. */
    public AuthService(
        AppUserRepository users,
        OrganizationRepository organizations,
        AccountRepository accounts,
        LedgerService ledgerService,
        PasswordEncoder passwords,
        ApplicationEventPublisher events
    ) {
        this.users = users;
        this.organizations = organizations;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.passwords = passwords;
        this.events = events;
    }

    public record Registration(AppUser user, Organization organization) {}

    /**
     * Registers an organization and active user with a trimmed, lowercase email and
     * encoded password. In one transaction, funds new accounts with 50,000 USD and
     * 10,000 EUR through the ledger and publishes a welcome-email request.
     *
     * @return the saved user and organization
     * @throws DuplicateEmailException if the email exists or the named email constraint fails
     * @throws IllegalArgumentException if the normalized tax ID is empty or too long
     * @throws IllegalStateException if a seeded clearing account is missing
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
            Account usdAccount = accounts.save(
                Account.open(org.id(), AccountType.USER, "USD")
            );
            Account eurAccount = accounts.save(
                Account.open(org.id(), AccountType.USER, "EUR")
            );
            UUID clearingUsd = clearingAccountId("USD");
            UUID clearingEur = clearingAccountId("EUR");
            // One global lock order for every row this transaction
            // touches (shared with escrow workflows via LedgerService).
            ledgerService.lockInOrder(
                java.util.List.of(
                    usdAccount.id(),
                    eurAccount.id(),
                    clearingUsd,
                    clearingEur
                )
            );
            ledgerService.transfer(
                clearingUsd,
                usdAccount.id(),
                MoneyAmounts.of(SANDBOX_USD, "USD"),
                "Sandbox opening balance",
                null
            );
            ledgerService.transfer(
                clearingEur,
                eurAccount.id(),
                MoneyAmounts.of(SANDBOX_EUR, "EUR"),
                "Sandbox opening balance",
                null
            );
            events.publishEvent(
                new WelcomeEmailRequested(user.email(), org.legalName())
            );
            return new Registration(user, org);
        } catch (DataIntegrityViolationException e) {
            // On PostgreSQL the failed statement already aborted the
            // transaction — no re-query is possible here, so match on the
            // named constraint and rethrow everything else.
            if (
                e.getMessage() != null
                    && e.getMessage().contains("app_users_email_uq")
            ) {
                throw new DuplicateEmailException("Email is already registered");
            }
            throw e;
        }
    }

    /**
     * Clearing accounts are seeded by the V1 migration — fail fast if
     * one is missing rather than racing another transaction to create it
     * (a failed insert would abort this transaction anyway).
     */
    private UUID clearingAccountId(String currency) {
        return accounts
            .findSystemAccountId(AccountType.ESCROWRAIL_CLEARING, currency)
            .orElseThrow(() ->
                new IllegalStateException(
                    "Clearing account missing for " + currency
                )
            );
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
        if (!user.active()) {
            throw new UnauthorizedException("Account is deactivated");
        }
        return user;
    }
}
