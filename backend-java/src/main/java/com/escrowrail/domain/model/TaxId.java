package com.escrowrail.domain.model;

import java.util.Objects;

/**
 * UBO tax identifier, normalized on construction for format-insensitive
 * equality — the anti-collusion guardrail compares value objects, not strings.
 */
public record TaxId(String value) {

    /**
     * Normalized: trims, drops ASCII whitespace and {@code .-_/} separators,
     * uppercases with the root locale.
     *
     * @throws NullPointerException if the value is null
     * @throws IllegalArgumentException if the normalized value is empty or exceeds 64 characters
     */
    public TaxId {
        Objects.requireNonNull(value, "tax id");
        // Format-insensitive equality: separators and case are ignored so
        // "12-345" and "12345" are the same UBO.
        value = value.strip()
            .replaceAll("[\\s.\\-_/]", "")
            .toUpperCase(java.util.Locale.ROOT);
        if (value.isEmpty() || value.length() > 64) {
            throw new IllegalArgumentException("Invalid tax id");
        }
    }
}
