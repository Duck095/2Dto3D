package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.PaymentProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "payment_callback_events")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCallbackEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "callback_event_id")
    private Long callbackEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private PaymentProvider provider;

    @Column(name = "provider_event_key", nullable = false, length = 255)
    private String providerEventKey;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "event_digest", nullable = false, length = 64)
    private String eventDigest;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
