package com.wise.escrow.config;

import com.wise.escrow.infrastructure.persistence.FieldEncryptionConverter;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Supplies the AES-256-GCM field-encryption key to the JPA converter. */
@Configuration
public class EncryptionConfig {

    private final String base64Key;

    /** Creates a new EncryptionConfig. */
    public EncryptionConfig(@Value("${wise.encryption.key}") String base64Key) {
        this.base64Key = base64Key;
    }

    /** Initializes the converter key before any entity is loaded. */
    @PostConstruct
    void init() {
        FieldEncryptionConverter.init(base64Key);
    }
}
