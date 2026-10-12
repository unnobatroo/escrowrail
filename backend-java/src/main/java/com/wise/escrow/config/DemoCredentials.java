package com.wise.escrow.config;

import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Demo persona credentials, resolved once per boot. Passwords come from
 * {@code DEMO_BUYER_PASSWORD} / {@code DEMO_SELLER_PASSWORD}; any unset value
 * is generated randomly and logged so local/demo testers can still sign in.
 * Exists only in {@code dev}/{@code demo}/{@code test} — prod never seeds
 * personas and never exposes the demo gateway.
 */
@Component
@Profile({"dev", "demo", "test"})
public class DemoCredentials {

    private static final Logger log = LoggerFactory.getLogger(DemoCredentials.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String buyerPassword;
    private final String sellerPassword;

    public DemoCredentials(
            @Value("${DEMO_BUYER_PASSWORD:}") String buyer,
            @Value("${DEMO_SELLER_PASSWORD:}") String seller,
            @Value("${wise.demo.buyer-email:buyer@demo.com}") String buyerEmail,
            @Value("${wise.demo.seller-email:seller@demo.com}") String sellerEmail) {
        this.buyerPassword = resolve(buyer, buyerEmail);
        this.sellerPassword = resolve(seller, sellerEmail);
    }

    private static String resolve(String configured, String email) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        log.info("demo persona {} — no password configured, generated: {}", email, generated);
        return generated;
    }

    public String buyerPassword() {
        return buyerPassword;
    }

    public String sellerPassword() {
        return sellerPassword;
    }
}
