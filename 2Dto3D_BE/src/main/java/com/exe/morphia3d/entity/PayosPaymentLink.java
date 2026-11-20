package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.enums.PayosPaymentProvenance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "payos_payment_links")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayosPaymentLink {
    @Id
    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, length = 16, updatable = false)
    private PayosPaymentProvenance provenance;

    @Column(name = "order_code", nullable = false, unique = true)
    private Long orderCode;

    @Column(name = "merchant_channel_id", nullable = false, length = 128)
    private String merchantChannelId;

    @Column(name = "amount_vnd", nullable = false)
    private Long amountVnd;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "description", nullable = false, length = 9)
    private String description;

    @Column(name = "link_state", nullable = false, length = 24)
    private String linkState;

    @Column(name = "payment_link_id", length = 128, unique = true)
    private String paymentLinkId;

    @Column(name = "checkout_url_ciphertext", columnDefinition = "text")
    private String checkoutUrlCiphertext;

    @Column(name = "qr_payload_ciphertext", columnDefinition = "text")
    private String qrPayloadCiphertext;

    @Column(name = "receiving_bank_bin", length = 16)
    private String receivingBankBin;

    @Column(name = "receiving_account_number_ciphertext", columnDefinition = "text")
    private String receivingAccountNumberCiphertext;

    @Column(name = "receiving_account_name", length = 128)
    private String receivingAccountName;

    @Column(name = "encryption_key_version")
    private Short encryptionKeyVersion;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "provider_response_digest", length = 64)
    private String providerResponseDigest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "link_created_at")
    private Instant linkCreatedAt;
}
