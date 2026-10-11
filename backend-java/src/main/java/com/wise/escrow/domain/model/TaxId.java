package com.wise.escrow.domain.model;

import java.util.Objects;

/**
 * Ultimate-beneficial-owner tax identifier. Normalized on construction so
 * equality is format-insensitive; the anti-collusion guardrail compares
 * value objects, never raw strings.
 */
public record TaxId(String value) {

    public TaxId {
        Objects.requireNonNull(value, "tax id");
        value = value.strip().toUpperCase();
        if (value.isEmpty() || value.length() > 64) {
            throw new IllegalArgumentException("Invalid tax id: " + value);
        }
    }
}
