package com.escrowrail.domain.model;

import java.util.Objects;

/** UBO tax identifier, normalized on construction for format-insensitive equality — the anti-collusion guardrail compares value objects, not strings. */
public record TaxId(String value) {

    /** Normalizes: trims, drops whitespace + .-_/ separators, uppercases. */
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
