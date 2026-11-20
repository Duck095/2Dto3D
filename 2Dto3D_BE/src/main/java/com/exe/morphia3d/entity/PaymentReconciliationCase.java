package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.PaymentProvider;
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
@Table(name = "payment_reconciliation_cases")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReconciliationCase {
    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "order_id", nullable = false)
    private Integer orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private PaymentProvider provider;

    @Column(name = "provider_event_key", nullable = false, length = 255)
    private String providerEventKey;

    @Column(name = "event_digest", nullable = false, length = 64)
    private String eventDigest;

    @Column(name = "observed_amount_vnd", nullable = false)
    private Long observedAmountVnd;

    @Column(name = "provider_transaction_id", length = 255)
    private String providerTransactionId;

    @Column(name = "verification_source", nullable = false, length = 80)
    private String verificationSource;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    @Column(name = "conflict_reason", nullable = false, length = 500)
    private String conflictReason;

    @Column(name = "resolution_state", nullable = false, length = 16)
    private String resolutionState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
