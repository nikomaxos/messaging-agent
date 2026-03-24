package com.messagingagent.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption service for sensitive fields (API keys, SMSC passwords).
 * 
 * The encryption key is read from the ENCRYPTION_KEY environment variable (hex-encoded, 32 bytes).
 * If not set, a default dev key is used (NOT safe for production).
 * 
 * Format: ENC(base64(iv + ciphertext + tag))
 */
@Service
@Slf4j
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String PREFIX = "ENC(";
    private static final String SUFFIX = ")";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptionService(
            @Value("${app.encryption.key:}") String configuredKey) {
        
        String keyHex = configuredKey;
        if (keyHex == null || keyHex.isBlank()) {
            keyHex = System.getenv("ENCRYPTION_KEY");
        }
        
        if (keyHex == null || keyHex.isBlank()) {
            // Default dev key — 32 bytes hex
            keyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
            log.warn("⚠️ Using default encryption key — set ENCRYPTION_KEY env var for production!");
        }

        byte[] keyBytes = hexToBytes(keyHex);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("Encryption key must be 32 bytes (64 hex chars), got " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypt a plaintext string. Returns ENC(base64) format.
     * Returns null if input is null/blank.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        if (isEncrypted(plaintext)) return plaintext; // already encrypted

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            // Prepend IV to ciphertext
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return PREFIX + Base64.getEncoder().encodeToString(buffer.array()) + SUFFIX;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt an ENC(base64) string back to plaintext.
     * Returns the input as-is if it's not in ENC() format (backward compat with unencrypted values).
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return encrypted;
        if (!isEncrypted(encrypted)) return encrypted; // plaintext — return as-is

        try {
            String base64 = encrypted.substring(PREFIX.length(), encrypted.length() - SUFFIX.length());
            byte[] decoded = Base64.getDecoder().decode(base64);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext));
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX) && value.endsWith(SUFFIX);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
