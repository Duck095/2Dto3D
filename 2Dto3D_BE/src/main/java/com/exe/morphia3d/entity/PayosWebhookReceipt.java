package com.exe.morphia3d.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "payos_webhook_receipts")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayosWebhookReceipt {
    @Id
    @Column(name = "receipt_id", nullable = false, updatable = false)
    private UUID receiptId;

    @Column(name = "merchant_channel_id", nullable = false, length = 128)
    private String merchantChannelId;

    @Column(name = "bank_reference", nullable = false, length = 255)
    private String bankReference;

    @Column(name = "event_digest", nullable = false, length = 64)
    private String eventDigest;

    @Column(name = "attempt_id")
    private UUID attemptId;

    @Column(name = "order_code", nullable = false)
    private Long orderCode;

    @Column(name = "payment_link_id", length = 128)
    private String paymentLinkId;

    @Column(name = "amount_vnd", nullable = false)
    private Long amountVnd;

    @Column(name = "recipient_account_ciphertext", nullable = false, columnDefinition = "text")
    private String recipientAccountCiphertext;

    @Column(name = "encryption_key_version", nullable = false)
    private Short encryptionKeyVersion;

    @Column(name = "provider_status", nullable = false, length = 32)
    private String providerStatus;

    @Column(name = "disposition", nullable = false, length = 32)
    private String disposition;

    @Column(name = "signature_verified", nullable = false)
    private boolean signatureVerified;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "signature_verified_at", nullable = false)
    private Instant signatureVerifiedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}
