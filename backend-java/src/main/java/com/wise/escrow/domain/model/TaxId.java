package com.escrowrail.escrow.domain.model;

import java.util.Objects;

/**
 * Ultimate-beneficial-owner tax identifier. Normalized on construction so
 * equality is format-insensitive; the anti-collusion guardrail compares
 * value objects, never raw strings.
 */
public record TaxId(String value) {

    /**
     * Strips surrounding whitespace, removes ASCII whitespace and {@code .-_/}
     * separators, and uppercases using the root locale for equality comparisons.
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
