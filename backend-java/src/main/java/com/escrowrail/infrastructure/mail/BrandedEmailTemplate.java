package com.escrowrail.infrastructure.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads the MJML-compiled transactional template (source:
 * {@code mail-templates/transactional.mjml}, regenerate with
 * {@code npx mjml transactional.mjml -o ../backend-java/src/main/resources/mail/branded.html})
 * and substitutes {@code {{TOKEN}}} placeholders.
 */
final class BrandedEmailTemplate {

    private static final String TEMPLATE;

    static {
        try (var in = BrandedEmailTemplate.class
                .getResourceAsStream("/mail/branded.html")) {
            TEMPLATE = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("mail/branded.html missing", e);
        }
    }

    private BrandedEmailTemplate() {}

    static String render(Map<String, String> tokens) {
        String html = TEMPLATE;
        for (var e : tokens.entrySet()) {
            html = html.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return html;
    }
}
