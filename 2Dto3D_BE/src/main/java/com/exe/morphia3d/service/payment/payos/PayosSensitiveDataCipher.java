package com.exe.morphia3d.service.payment.payos;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** AES-GCM envelope encryption for payOS redirect, QR, and receiving-account fields. */
public final class PayosSensitiveDataCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final Map<Integer, byte[]> keys;
    private final int activeKeyVersion;
    private final SecureRandom random;

    public PayosSensitiveDataCipher(Map<Integer, byte[]> keys, int activeKeyVersion) {
        this(keys, activeKeyVersion, new SecureRandom());
    }

    PayosSensitiveDataCipher(Map<Integer, byte[]> keys, int activeKeyVersion, SecureRandom random) {
        if (keys == null || keys.isEmpty() || random == null || !keys.containsKey(activeKeyVersion)) {
            throw new IllegalArgumentException("An active payOS encryption key version is required");
        }
        Map<Integer, byte[]> copied = new HashMap<>();
        keys.forEach((version, key) -> {
            if (version == null || version <= 0 || key == null
                    || (key.length != 16 && key.length != 24 && key.length != 32)) {
                throw new IllegalArgumentException("PayOS encryption keys must be AES keys with a positive version");
            }
            copied.put(version, Arrays.copyOf(key, key.length));
        });
        this.keys = Map.copyOf(copied);
        this.activeKeyVersion = activeKeyVersion;
        this.random = random;
    }

    public EncryptedValue encrypt(String plaintext, String associatedData) {
        return encrypt(plaintext, activeKeyVersion, associatedData);
    }

    public EncryptedValue encrypt(String plaintext, int keyVersion, String associatedData) {
        if (plaintext == null || associatedData == null) throw new IllegalArgumentException("Sensitive value and AAD are required");
        byte[] key = keys.get(keyVersion);
        if (key == null) throw new PayosSensitiveDataException("PAYOS_ENCRYPTION_KEY_VERSION_UNKNOWN");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] combined = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return new EncryptedValue(keyVersion, Base64.getUrlEncoder().withoutPadding().encodeToString(combined));
        } catch (GeneralSecurityException error) {
            throw new PayosSensitiveDataException("PAYOS_ENCRYPTION_FAILED", error);
        }
    }

    public String decrypt(EncryptedValue value, String associatedData) {
        if (value == null || associatedData == null) throw new IllegalArgumentException("Encrypted value and AAD are required");
        byte[] key = keys.get(value.keyVersion());
        if (key == null) throw new PayosSensitiveDataException("PAYOS_ENCRYPTION_KEY_VERSION_UNKNOWN");
        final byte[] combined;
        try { combined = Base64.getUrlDecoder().decode(value.ciphertext()); }
        catch (IllegalArgumentException malformed) { throw new PayosSensitiveDataException("PAYOS_CIPHERTEXT_MALFORMED", malformed); }
        if (combined.length <= NONCE_BYTES + TAG_BITS / 8) throw new PayosSensitiveDataException("PAYOS_CIPHERTEXT_MALFORMED");
        byte[] nonce = Arrays.copyOf(combined, NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(combined, NONCE_BYTES, combined.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (AEADBadTagException tampered) {
            throw new PayosSensitiveDataException("PAYOS_CIPHERTEXT_AUTHENTICATION_FAILED", tampered);
        } catch (GeneralSecurityException error) {
            throw new PayosSensitiveDataException("PAYOS_DECRYPTION_FAILED", error);
        }
    }

    public EncryptedValue rotate(EncryptedValue oldValue, int newKeyVersion, String associatedData) {
        return encrypt(decrypt(oldValue, associatedData), newKeyVersion, associatedData);
    }

    public record EncryptedValue(int keyVersion, String ciphertext) { }
}
