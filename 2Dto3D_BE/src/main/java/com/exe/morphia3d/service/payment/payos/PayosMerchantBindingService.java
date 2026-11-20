package com.exe.morphia3d.service.payment.payos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Read-only trusted-binding lookup. It never verifies, revokes, or authorizes LIVE creation. */
@Service
public class PayosMerchantBindingService {
    private static final Pattern ACCOUNT_FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BANK_BIN = Pattern.compile("[0-9]{6,8}");

    private final JdbcTemplate jdbc;

    public PayosMerchantBindingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PayosTrustedMerchantBinding> findVerifiedBinding(
            String channelId, Integer version, String expectedBankBin, String expectedAccountFingerprint) {
        if (blank(channelId) || version == null || version <= 0
                || expectedBankBin == null || !BANK_BIN.matcher(expectedBankBin).matches()
                || expectedAccountFingerprint == null || !ACCOUNT_FINGERPRINT.matcher(expectedAccountFingerprint).matches()) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT binding_id, binding_version, merchant_channel_id, client_id,
                               api_key_secret_ref, checksum_key_secret_ref, secret_version_ref,
                               receiving_bank_bin, receiving_account_fingerprint,
                               verification_evidence_ref, approved_by, verified_at
                        FROM payos_merchant_bindings
                        WHERE merchant_channel_id = ? AND binding_version = ?
                          AND binding_version = (
                              SELECT max(current_binding.binding_version)
                              FROM payos_merchant_bindings current_binding
                              WHERE current_binding.merchant_channel_id = ?
                          )
                          AND binding_state = 'VERIFIED' AND verified_at IS NOT NULL
                          AND approved_by IS NOT NULL AND approved_at IS NOT NULL
                          AND verification_evidence_ref IS NOT NULL
                          AND revoked_at IS NULL AND revoked_by IS NULL
                        """,
                (result, row) -> new PayosTrustedMerchantBinding(
                        result.getObject("binding_id", UUID.class), result.getInt("binding_version"),
                        result.getString("merchant_channel_id"), result.getString("client_id"),
                        result.getString("api_key_secret_ref"), result.getString("checksum_key_secret_ref"),
                        result.getString("secret_version_ref"), result.getString("receiving_bank_bin"),
                        result.getString("receiving_account_fingerprint"), result.getString("verification_evidence_ref"),
                        result.getString("approved_by"), result.getObject("verified_at", OffsetDateTime.class).toInstant()),
                channelId, version, channelId).stream()
                .filter(binding -> expectedBankBin.equals(binding.receivingBankBin())
                        && expectedAccountFingerprint.equals(binding.receivingAccountFingerprint())
                        && validSecretReference(binding.apiKeySecretRef())
                        && validSecretReference(binding.checksumKeySecretRef())
                        && validSecretReference(binding.secretVersionRef()))
                .findFirst();
    }

    private boolean validSecretReference(String reference) {
        if (blank(reference)) return false;
        try {
            URI uri = URI.create(reference);
            return uri.getScheme() != null && uri.getUserInfo() == null && uri.getFragment() == null
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
