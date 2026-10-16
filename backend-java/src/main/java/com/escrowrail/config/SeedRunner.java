package com.escrowrail.config;

import com.escrowrail.application.service.AuthService;
import com.escrowrail.application.service.LedgerService;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.model.MoneyAmounts;
import com.escrowrail.domain.repository.AccountRepository;
import com.escrowrail.domain.repository.AppUserRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Idempotent demo seed: buyer + seller orgs with ledger-granted operating balances (registration grants nothing). dev/demo/test only — prod never seeds. */
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
        // Buyer: 50k USD. Seller: 10k EUR.
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
        // Trusted fixtures — skip the email gate and onboarding window.
        users.save(registration.user().verified().onboarded());
        grant(registration.organization().id(), grantCurrency, grantAmount);
        log.info("Seeded demo tenant {}", legalName);
    }

    /** Grants the demo balance clearing -&gt; user through the ledger. */
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
