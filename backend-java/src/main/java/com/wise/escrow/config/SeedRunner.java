package com.wise.escrow.config;

import com.wise.escrow.application.service.AuthService;
import com.wise.escrow.application.service.LedgerService;
import com.wise.escrow.domain.model.AccountType;
import com.wise.escrow.domain.model.MoneyAmounts;
import com.wise.escrow.domain.repository.AccountRepository;
import com.wise.escrow.domain.repository.AppUserRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Idempotent demo seed: on an unseeded database, provisions a buyer and a
 * seller organization (with sandbox USD/EUR balances via the normal
 * registration path) so the dashboard and E2E flows have usable tenants.
 * Runs only when {@code wise.seed.enabled} is true and each demo email is
 * absent — safe to run on every boot.
 */
@Component
public class SeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final AuthService auth;
    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final LedgerService ledgerService;
    private final boolean enabled;
    private final String demoPassword;

    /** Creates a new SeedRunner. */
    public SeedRunner(
        AuthService auth,
        AppUserRepository users,
        AccountRepository accounts,
        LedgerService ledgerService,
        @Value("${wise.seed.enabled:true}") boolean enabled,
        @Value("${wise.seed.password:WiseDemo123!}") String demoPassword
    ) {
        this.auth = auth;
        this.users = users;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.enabled = enabled;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        // Buyer trades in USD only: registration grants both sandbox
        // balances, then the EUR grant is returned to clearing so the
        // seeded shape is 50,000 USD / 0 EUR.
        seed("buyer@demo.com", "Acme Industrial US", "US-1001", "US", "EUR");
        seed("seller@demo.com", "Berlin Robotics GmbH", "DE-2002", "DE", "USD");
    }

    private void seed(
            String email,
            String legalName,
            String taxId,
            String country,
            String zeroCurrency
    ) {
        if (users.findByEmail(email).isPresent()) {
            return;
        }
        var registration = auth.register(email, demoPassword, legalName, taxId, country);
        // Demo tenants are trusted fixtures — skip the email gate.
        users.save(registration.user().verified());
        zeroBalance(registration.organization().id(), zeroCurrency);
        log.info("Seeded demo tenant {}", legalName);
    }

    /**
     * Returns the freshly granted sandbox balance in {@code currency} to the
     * clearing account through the ledger — the seeded demo shape keeps only
     * the operating currency each tenant trades in.
     */
    private void zeroBalance(java.util.UUID entityId, String currency) {
        var account = accounts
            .findByEntityIdAndTypeAndCurrency(entityId, AccountType.USER, currency)
            .orElseThrow(() -> new IllegalStateException(
                "Seeded account missing for " + currency));
        var clearing = accounts
            .findSystemAccountId(AccountType.WISE_CLEARING, currency)
            .orElseThrow(() -> new IllegalStateException(
                "Clearing account missing for " + currency));
        BigDecimal balance = account.balance()
            .getNumber()
            .numberValue(BigDecimal.class);
        if (balance.signum() > 0) {
            ledgerService.transfer(
                account.id(),
                clearing,
                MoneyAmounts.of(balance, currency),
                "Sandbox balance reset",
                null
            );
        }
    }
}
