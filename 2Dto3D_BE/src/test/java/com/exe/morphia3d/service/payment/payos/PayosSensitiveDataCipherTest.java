package com.exe.morphia3d.service.payment.payos;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayosSensitiveDataCipherTest {
    @Test
    void encryptsWithAuthenticatedEncryptionAndDecryptsByPersistedKeyVersion() {
        PayosSensitiveDataCipher cipher = new PayosSensitiveDataCipher(Map.of(
                1, new byte[16], 2, new byte[32]), 2);

        var encrypted = cipher.encrypt("https://pay.payos.vn/web/secret", "attempt-1|checkout-url");

        assertThat(encrypted.keyVersion()).isEqualTo(2);
        assertThat(encrypted.ciphertext()).doesNotContain("pay.payos.vn");
        assertThat(cipher.decrypt(encrypted, "attempt-1|checkout-url")).isEqualTo("https://pay.payos.vn/web/secret");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "attempt-2|checkout-url"))
                .isInstanceOf(PayosSensitiveDataException.class);
        // Change a high-order encoded character; flipping the final Base64URL character can alter only unused pad bits.
        String tampered = (encrypted.ciphertext().startsWith("A") ? "B" : "A")
                + encrypted.ciphertext().substring(1);
        assertThatThrownBy(() -> cipher.decrypt(new PayosSensitiveDataCipher.EncryptedValue(encrypted.keyVersion(), tampered),
                "attempt-1|checkout-url")).isInstanceOf(PayosSensitiveDataException.class);
    }

    @Test
    void rotatesCiphertextWithoutChangingPlaintextAndRejectsUnknownKeyVersions() {
        PayosSensitiveDataCipher cipher = new PayosSensitiveDataCipher(Map.of(
                1, new byte[16], 2, new byte[32]), 2);
        var old = cipher.encrypt("QR:VND:500", 1, "attempt-1|qr-payload");

        var rotated = cipher.rotate(old, 2, "attempt-1|qr-payload");

        assertThat(rotated.keyVersion()).isEqualTo(2);
        assertThat(rotated.ciphertext()).isNotEqualTo(old.ciphertext());
        assertThat(cipher.decrypt(rotated, "attempt-1|qr-payload")).isEqualTo("QR:VND:500");
        assertThatThrownBy(() -> new PayosSensitiveDataCipher(Map.of(1, new byte[15]), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
