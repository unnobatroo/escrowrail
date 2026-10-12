package com.escrowrail.escrow.application.service;

import com.escrowrail.escrow.domain.exception.NotFoundException;
import com.escrowrail.escrow.domain.exception.ValidationException;
import com.escrowrail.escrow.domain.model.Account;
import com.escrowrail.escrow.domain.model.AccountType;
import com.escrowrail.escrow.domain.model.AppUser;
import com.escrowrail.escrow.domain.model.MoneyAmounts;
import com.escrowrail.escrow.domain.model.Organization;
import com.escrowrail.escrow.domain.model.TaxId;
import com.escrowrail.escrow.domain.repository.AccountRepository;
import com.escrowrail.escrow.domain.repository.AppUserRepository;
import com.escrowrail.escrow.domain.repository.OrganizationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service tenant profile: legal name and tax ID updates, plus the
 * one-time onboarding deposit window. Profile edits are tenant-scoped by
 * the authenticated entity — there is no admin path.
 */
@Service
public class ProfileService {

    /**
     * Country-format tax IDs: two-letter country, a scheme code, then the
     * serial — e.g. {@code GB-CRN-882190}, {@code US-EIN-4471},
     * {@code DE-HRB-90210}.
     */
    private static final Pattern TAX_ID_FORMAT =
            Pattern.compile("^[A-Z]{2}-[A-Z]{2,5}-[0-9]{3,10}$");

    private static final BigDecimal MAX_DEPOSIT = new BigDecimal("100000.00");

    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final AccountRepository accounts;
    private final LedgerService ledgerService;

    /** Creates a new ProfileService. */
    public ProfileService(
        AppUserRepository users,
        OrganizationRepository organizations,
        AccountRepository accounts,
        LedgerService ledgerService
    ) {
        this.users = users;
        this.organizations = organizations;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
    }

    public record Result(AppUser user, Organization organization) {}

    /**
     * Applies profile edits and any onboarding deposit in one transaction.
     * A deposit request outside the onboarding window is rejected outright —
     * the faucet must never be usable twice.
     *
     * @return the persisted user and organization
     * @throws NotFoundException if the user or organization is gone
     * @throws ValidationException if the tax ID fails the country format, a
     *     name is blank, or deposits arrive after onboarding completed
     */
    @Transactional
    public Result update(
        UUID userId,
        UUID entityId,
        String legalName,
        String taxId,
        java.util.Map<String, BigDecimal> deposits
    ) {
        AppUser user = users
            .findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found"));
        Organization org = organizations
            .findById(entityId)
            .orElseThrow(() -> new NotFoundException("Organization not found"));

        String name = legalName == null ? org.legalName() : legalName.strip();
        if (name.isEmpty() || name.length() > 255) {
            throw new ValidationException("Legal name must be 1-255 characters");
        }
        TaxId normalized = org.uboTaxId();
        if (taxId != null && !taxId.isBlank()) {
            String candidate = taxId.strip().toUpperCase(java.util.Locale.ROOT);
            if (!TAX_ID_FORMAT.matcher(candidate).matches()) {
                throw new ValidationException(
                    "Tax ID must match <CC>-<SCHEME>-<SERIAL> (e.g. GB-CRN-882190)"
                );
            }
            normalized = new TaxId(candidate);
        }
        Organization updated = organizations.save(
            new Organization(
                org.id(), name, normalized, org.country(), org.createdAt()
            )
        );

        if (deposits != null && !deposits.isEmpty()) {
            if (user.onboardingCompleted()) {
                throw new ValidationException(
                    "Initial deposits are a one-time onboarding credit"
                );
            }
            for (var e : deposits.entrySet()) {
                deposit(user, e.getKey(), e.getValue());
            }
        }
        return new Result(users.save(user.onboarded()), updated);
    }

    /** Clearing -> user credit through the hash-chained ledger. */
    private void deposit(AppUser user, String rawCurrency, BigDecimal amount) {
        String currency = rawCurrency == null
            ? ""
            : rawCurrency.strip().toUpperCase(java.util.Locale.ROOT);
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        if (amount.compareTo(MAX_DEPOSIT) > 0) {
            throw new ValidationException(
                "Onboarding deposit is capped at " + MAX_DEPOSIT + " per currency"
            );
        }
        Account target = accounts
            .findByEntityIdAndTypeAndCurrency(
                user.entityId(), AccountType.USER, currency
            )
            .orElseThrow(() ->
                new NotFoundException("No " + currency + " account")
            );
        UUID clearing = accounts
            .findSystemAccountId(AccountType.ESCROWRAIL_CLEARING, currency)
            .orElseThrow(() ->
                new IllegalStateException("Clearing account missing")
            );
        ledgerService.lockInOrder(List.of(target.id(), clearing));
        ledgerService.transfer(
            clearing,
            target.id(),
            MoneyAmounts.of(amount, currency),
            "Onboarding deposit",
            null
        );
    }
}
