package com.messagingagent.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter that transparently encrypts/decrypts string fields.
 * Apply with @Convert(converter = EncryptedStringConverter.class) on entity fields.
 * 
 * Backward compatible: plaintext values in the DB are returned as-is and will
 * be encrypted on the next write.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static EncryptionService encryptionService;

    public EncryptedStringConverter(EncryptionService service) {
        EncryptedStringConverter.encryptionService = service;
    }

    // JPA may use no-arg constructor
    public EncryptedStringConverter() {}

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) return attribute;
        if (encryptionService == null) return attribute; // not yet initialized
        return encryptionService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return dbData;
        if (encryptionService == null) return dbData; // not yet initialized
        return encryptionService.decrypt(dbData);
    }
}
