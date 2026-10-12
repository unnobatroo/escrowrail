package com.escrowrail.escrow.config;

import com.escrowrail.escrow.application.service.AuthService;
import com.escrowrail.escrow.application.service.LedgerService;
import com.escrowrail.escrow.domain.model.AccountType;
import com.escrowrail.escrow.domain.model.MoneyAmounts;
import com.escrowrail.escrow.domain.repository.AccountRepository;
import com.escrowrail.escrow.domain.repository.AppUserRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Idempotent demo seed: on an unseeded database, provisions a buyer and a
 * seller organization and grants each their operating balance through the
 * ledger — registration itself grants nothing, so demo funding must be
 * explicit here. Exists only under {@code dev}/{@code demo}/{@code test} —
 * production boots against a clean schema and never seeds automatically.
 */
@Component
@Profile({"dev", "demo", "test"})
public class SeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final AuthService auth;
    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final LedgerService ledgerService;
    private final boolean enabled;
    private final DemoCredentials credentials;

    /** Creates a new SeedRunner. */
    public SeedRunner(
        AuthService auth,
        AppUserRepository users,
        AccountRepository accounts,
        LedgerService ledgerService,
        DemoCredentials credentials,
        @Value("${escrowrail.seed.enabled:true}") boolean enabled
    ) {
        this.auth = auth;
        this.users = users;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.credentials = credentials;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        // Buyer trades in USD only (50,000 USD / 0 EUR); the seller holds
        // its EUR operating balance (0 USD / 10,000 EUR).
        seed("buyer@demo.com", "Acme Industrial US", "US-1001", "US",
            "USD", new BigDecimal("50000.00"), credentials.buyerPassword());
        seed("seller@demo.com", "Berlin Robotics GmbH", "DE-2002", "DE",
            "EUR", new BigDecimal("10000.00"), credentials.sellerPassword());
    }

    private void seed(
            String email,
            String legalName,
            String taxId,
            String country,
            String grantCurrency,
            BigDecimal grantAmount,
            String password
    ) {
        if (users.findByEmail(email).isPresent()) {
            return;
        }
        var registration = auth.register(email, password, legalName, taxId, country);
        // Demo tenants are trusted fixtures — skip the email gate and the
        // onboarding setup window.
        users.save(registration.user().verified().onboarded());
        grant(registration.organization().id(), grantCurrency, grantAmount);
        log.info("Seeded demo tenant {}", legalName);
    }

    /**
     * Credits the demo operating balance clearing -&gt; user through the
     * ledger — the seeded shape grants only the currency each tenant
     * trades in.
     */
    private void grant(java.util.UUID entityId, String currency, BigDecimal amount) {
        var account = accounts
            .findByEntityIdAndTypeAndCurrency(entityId, AccountType.USER, currency)
            .orElseThrow(() -> new IllegalStateException(
                "Seeded account missing for " + currency));
        var clearing = accounts
            .findSystemAccountId(AccountType.ESCROWRAIL_CLEARING, currency)
            .orElseThrow(() -> new IllegalStateException(
                "Clearing account missing for " + currency));
        ledgerService.transfer(
            clearing,
            account.id(),
            MoneyAmounts.of(amount, currency),
            "Demo opening balance",
            null
        );
    }
}
