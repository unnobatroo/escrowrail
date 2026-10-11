package com.wise.escrow.config;

import com.wise.escrow.application.service.AuthService;
import com.wise.escrow.domain.repository.AppUserRepository;
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
    private final boolean enabled;
    private final String demoPassword;

    /** Creates a new SeedRunner. */
    public SeedRunner(
        AuthService auth,
        AppUserRepository users,
        @Value("${wise.seed.enabled:true}") boolean enabled,
        @Value("${wise.seed.password:demo1234}") String demoPassword
    ) {
        this.auth = auth;
        this.users = users;
        this.enabled = enabled;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        seed(
            "buyer@wise.demo",
            "Acme Buying Corporation",
            "US-ACME-9821",
            "US"
        );
        seed(
            "seller@wise.demo",
            "Globex Selling GmbH",
            "DE-GLOBEX-4477",
            "DE"
        );
    }

    private void seed(String email, String legalName, String taxId, String country) {
        if (users.findByEmail(email).isPresent()) {
            return;
        }
        auth.register(email, demoPassword, legalName, taxId, country);
        log.info("Seeded demo tenant {}", legalName);
    }
}
