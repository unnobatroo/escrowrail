package com.wise.escrow.application.service;

import com.wise.escrow.application.dto.Events.WelcomeEmailRequested;
import com.wise.escrow.domain.exception.DuplicateEmailException;
import com.wise.escrow.domain.exception.UnauthorizedException;
import com.wise.escrow.domain.model.Account;
import com.wise.escrow.domain.model.AccountType;
import com.wise.escrow.domain.model.AppUser;
import com.wise.escrow.domain.model.MoneyAmounts;
import com.wise.escrow.domain.model.Organization;
import com.wise.escrow.domain.repository.AccountRepository;
import com.wise.escrow.domain.repository.AppUserRepository;
import com.wise.escrow.domain.repository.OrganizationRepository;
import java.math.BigDecimal;
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

    public AuthService(
            AppUserRepository users,
            OrganizationRepository organizations,
            AccountRepository accounts,
            LedgerService ledgerService,
            PasswordEncoder passwords,
            ApplicationEventPublisher events) {
        this.users = users;
        this.organizations = organizations;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.passwords = passwords;
        this.events = events;
    }

    public record Registration(AppUser user, Organization organization) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Registration register(
            String email, String password, String legalName, String uboTaxId, String country) {
        String normalized = email.toLowerCase().strip();
        if (users.findByEmail(normalized).isPresent()) {
            throw new DuplicateEmailException("Email is already registered");
        }
        try {
            Organization org = organizations.save(Organization.register(legalName, uboTaxId, country));
            AppUser user = users.save(AppUser.register(normalized, passwords.encode(password), org.id()));
            for (var pair : new String[][] {{"USD", "50000"}, {"EUR", "10000"}}) {
                String currency = pair[0];
                Account account = accounts.save(Account.open(org.id(), AccountType.USER, currency));
                Account clearing = accounts
                        .findSystemAccountForUpdate(AccountType.WISE_CLEARING, currency)
                        .orElseGet(() -> accounts.save(Account.open(null, AccountType.WISE_CLEARING, currency)));
                ledgerService.transfer(
                        clearing.id(),
                        account.id(),
                        MoneyAmounts.of(new BigDecimal(pair[1]), currency),
                        "Sandbox opening balance",
                        null);
            }
            events.publishEvent(new WelcomeEmailRequested(user.email(), org.legalName()));
            return new Registration(user, org);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEmailException("Email is already registered");
        }
    }

    public AppUser authenticate(String email, String password) {
        AppUser user = users
                .findByEmail(email.toLowerCase().strip())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        if (!passwords.matches(password, user.passwordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        if (!user.active()) {
            throw new UnauthorizedException("Account is deactivated");
        }
        return user;
    }
}
