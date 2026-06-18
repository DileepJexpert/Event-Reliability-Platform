package com.eventreliability;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.security.PayloadProtectionService;

import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadProtectionServiceTest {

    @Test
    void encryptionRoundTrip() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String keyBase64 = Base64.getEncoder().encodeToString(key);

        PayloadProtectionService service = service(true, keyBase64, false);
        byte[] original = "{\"account\":\"12345\"}".getBytes(StandardCharsets.UTF_8);

        String stored = service.encryptForStorage(original);
        assertThat(stored).startsWith("ENC:");
        assertThat(stored).isNotEqualTo(Base64.getEncoder().encodeToString(original));

        byte[] decrypted = service.decryptForReplay(stored);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void passthroughWhenEncryptionDisabled() {
        PayloadProtectionService service = service(false, null, false);
        byte[] original = "hello".getBytes(StandardCharsets.UTF_8);

        String stored = service.encryptForStorage(original);
        assertThat(stored).doesNotStartWith("ENC:");
        assertThat(stored).isEqualTo(Base64.getEncoder().encodeToString(original));

        byte[] decoded = service.decryptForReplay(stored);
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void piiMaskingReplacesKnownPatterns() {
        PayloadProtectionService service = service(false, null, true);
        String payload = "{\"ssn\":\"123-45-6789\",\"email\":\"jane@bank.com\",\"card\":\"4111-1111-1111-1111\",\"iban\":\"GB29NWBK60161331926819\"}";
        String b64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        String masked = service.maskForDisplay(b64);
        String maskedText = new String(Base64.getDecoder().decode(masked), StandardCharsets.UTF_8);

        assertThat(maskedText).contains("[MASKED:ssn]");
        assertThat(maskedText).contains("[MASKED:email]");
        assertThat(maskedText).contains("[MASKED:credit-card]");
        assertThat(maskedText).contains("[MASKED:iban]");
        assertThat(maskedText).doesNotContain("123-45-6789");
        assertThat(maskedText).doesNotContain("jane@bank.com");
        assertThat(maskedText).doesNotContain("4111-1111-1111-1111");
        assertThat(maskedText).doesNotContain("GB29NWBK60161331926819");
    }

    @Test
    void maskingDisabledReturnsPlaintext() {
        PayloadProtectionService service = service(false, null, false);
        String payload = "{\"ssn\":\"123-45-6789\"}";
        String b64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        String result = service.maskForDisplay(b64);
        assertThat(result).isEqualTo(b64);
    }

    @Test
    void encryptedPayloadIsMaskedOnDisplay() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String keyBase64 = Base64.getEncoder().encodeToString(key);

        PayloadProtectionService service = service(true, keyBase64, true);
        byte[] original = "{\"ssn\":\"123-45-6789\",\"name\":\"Jane\"}".getBytes(StandardCharsets.UTF_8);

        String stored = service.encryptForStorage(original);
        String masked = service.maskForDisplay(stored);
        String maskedText = new String(Base64.getDecoder().decode(masked), StandardCharsets.UTF_8);

        assertThat(maskedText).contains("[MASKED:ssn]");
        assertThat(maskedText).doesNotContain("123-45-6789");
        assertThat(maskedText).contains("Jane");
    }

    @Test
    void nullPayloadHandledGracefully() {
        PayloadProtectionService service = service(false, null, true);
        assertThat(service.encryptForStorage(null)).isNull();
        assertThat(service.decryptForReplay(null)).isNull();
        assertThat(service.maskForDisplay(null)).isNull();
    }

    @Test
    void encryptionEnabledWithoutKeyThrows() {
        assertThatThrownBy(() -> service(true, null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no encryption-key configured");
    }

    private static PayloadProtectionService service(boolean encrypt, String key, boolean mask) {
        ReliabilityProperties props = new ReliabilityProperties(
                "reliability.", null, null, null, null, null, null, null, null, null, null,
                new ReliabilityProperties.PayloadProtection(encrypt, key, mask, List.of()), null, null);
        return new PayloadProtectionService(props);
    }
}
