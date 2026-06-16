package com.eventreliability.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.eventreliability.config.ReliabilityProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption at rest + regex-based PII masking for API display. Payloads are encrypted
 * before storage in the compacted state topic and decrypted only on replay (faithful re-drive) or
 * API display (with PII masked). Encrypted payloads are prefixed with {@code ENC:} so mixed
 * (pre-existing plaintext + new encrypted) deployments work transparently.
 */
@Service
public class PayloadProtectionService {

    private static final Logger log = LoggerFactory.getLogger(PayloadProtectionService.class);
    static final String ENC_PREFIX = "ENC:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final boolean encryptionEnabled;
    private final SecretKey secretKey;
    private final boolean maskingEnabled;
    private final List<MaskRule> maskRules;
    private final SecureRandom random = new SecureRandom();

    public PayloadProtectionService(ReliabilityProperties props) {
        ReliabilityProperties.PayloadProtection config = props.payloadProtection();
        this.encryptionEnabled = config.encryptionEnabled();
        this.maskingEnabled = config.maskingEnabled();

        String keyBase64 = config.encryptionKey();
        if (keyBase64 != null && !keyBase64.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64.trim());
            if (keyBytes.length != 32) {
                throw new IllegalStateException(
                        "payload-protection.encryption-key must be exactly 256 bits (32 bytes), got " + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } else {
            this.secretKey = null;
            if (encryptionEnabled) {
                throw new IllegalStateException(
                        "payload-protection.encryption-enabled=true but no encryption-key configured");
            }
        }

        this.maskRules = config.patterns().stream()
                .map(p -> new MaskRule(p.name(), Pattern.compile(p.regex())))
                .toList();
        if (encryptionEnabled) {
            log.info("Payload encryption enabled (AES-256-GCM)");
        }
        if (maskingEnabled) {
            log.info("PII masking enabled with {} pattern(s)", maskRules.size());
        }
    }

    /** Encrypt raw payload bytes for storage in the compacted state topic. */
    public String encryptForStorage(byte[] payload) {
        if (payload == null) return null;
        if (!encryptionEnabled) {
            return Base64.getEncoder().encodeToString(payload);
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(payload);
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("Payload encryption failed", ex);
        }
    }

    /** Decrypt stored payload for replay/quarantine — returns the original raw bytes. */
    public byte[] decryptForReplay(String payloadBase64) {
        if (payloadBase64 == null) return null;
        if (payloadBase64.startsWith(ENC_PREFIX)) {
            if (secretKey == null) {
                throw new IllegalStateException("Encrypted payload found but no decryption key configured");
            }
            return decryptRaw(payloadBase64.substring(ENC_PREFIX.length()));
        }
        return Base64.getDecoder().decode(payloadBase64);
    }

    /** Decrypt + PII-mask for API display — returns base64-encoded masked payload. */
    public String maskForDisplay(String payloadBase64) {
        if (payloadBase64 == null) return null;
        byte[] raw;
        if (payloadBase64.startsWith(ENC_PREFIX)) {
            if (secretKey == null) {
                throw new IllegalStateException("Encrypted payload found but no decryption key configured");
            }
            raw = decryptRaw(payloadBase64.substring(ENC_PREFIX.length()));
        } else {
            raw = Base64.getDecoder().decode(payloadBase64);
        }
        if (raw == null) return null;
        if (!maskingEnabled || maskRules.isEmpty()) {
            return Base64.getEncoder().encodeToString(raw);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        String masked = applyMaskRules(text);
        return Base64.getEncoder().encodeToString(masked.getBytes(StandardCharsets.UTF_8));
    }

    String applyMaskRules(String text) {
        String result = text;
        for (MaskRule rule : maskRules) {
            result = rule.pattern().matcher(result).replaceAll("[MASKED:" + rule.name() + "]");
        }
        return result;
    }

    private byte[] decryptRaw(String base64) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64);
            if (combined.length < GCM_IV_LENGTH) {
                throw new IllegalStateException("Encrypted payload too short");
            }
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_BITS, combined, 0, GCM_IV_LENGTH));
            return cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH);
        } catch (Exception ex) {
            throw new IllegalStateException("Payload decryption failed", ex);
        }
    }

    private record MaskRule(String name, Pattern pattern) {}
}
