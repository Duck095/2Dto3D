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
@Table(name = "payos_reconciliation_queries")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayosReconciliationQuery {
    @Id
    @Column(name = "query_id", nullable = false, updatable = false)
    private UUID queryId;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "merchant_channel_id", nullable = false, length = 128)
    private String merchantChannelId;

    @Column(name = "query_request_id", nullable = false, length = 128)
    private String queryRequestId;

    @Column(name = "order_code", nullable = false)
    private Long orderCode;

    @Column(name = "payment_link_id", length = 128)
    private String paymentLinkId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "outcome", nullable = false, length = 24)
    private String outcome;

    @Column(name = "provider_status", length = 32)
    private String providerStatus;

    @Column(name = "amount_vnd", nullable = false)
    private Long amountVnd;

    @Column(name = "amount_paid_vnd")
    private Long amountPaidVnd;

    @Column(name = "response_digest", length = 64)
    private String responseDigest;

    @Column(name = "signature_verified", nullable = false)
    private boolean signatureVerified;
}
