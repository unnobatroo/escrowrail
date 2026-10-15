package com.escrowrail.infrastructure.persistence.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM field-level encryption for sensitive columns (tax IDs).
 * Output is {@code base64(iv || ciphertext||tag)}; a fresh random 12-byte
 * IV is generated per write so identical plaintext never produces
 * identical ciphertext. The key is injected at boot by
 * {@code EncryptionConfig} — JPA instantiates converters itself, so the
 * key cannot be constructor-injected.
 */
@Converter
public class FieldEncryptionConverter implements AttributeConverter<String, String> {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile SecretKeySpec key;

    /** Installs the AES-256 key (base64-encoded 32 bytes). Fails fast on bad input. */
    public static void init(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                "ESCROWRAIL_FIELD_ENCRYPTION_KEY is required (base64, 32 bytes)"
            );
        }
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalStateException(
                "ESCROWRAIL_FIELD_ENCRYPTION_KEY must decode to 32 bytes, got " + raw.length
            );
        }
        key = new SecretKeySpec(raw, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.ENCRYPT_MODE,
                requireKey(),
                new GCMParameterSpec(TAG_BITS, iv)
            );
            byte[] ct = cipher.doFinal(
                attribute.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array()
            );
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(dbData);
            ByteBuffer buf = ByteBuffer.wrap(blob);
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                requireKey(),
                new GCMParameterSpec(TAG_BITS, iv)
            );
            return new String(
                cipher.doFinal(ct),
                java.nio.charset.StandardCharsets.UTF_8
            );
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Field decryption failed", e);
        }
    }

    private static SecretKeySpec requireKey() {
        SecretKeySpec k = key;
        if (k == null) {
            throw new IllegalStateException("Encryption key not initialized");
        }
        return k;
    }
}
