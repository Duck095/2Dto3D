package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.Payment;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.enums.PaymentStatus;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.PaymentRepository;
import com.exe.morphia3d.service.payment.provider.ProviderReference;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Qualifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentAttemptPersistenceService {
    private static final List<PaymentAttemptStatus> BLOCKING_STATUSES = List.of(
            PaymentAttemptStatus.INITIATED, PaymentAttemptStatus.SUBMITTING, PaymentAttemptStatus.PENDING,
            PaymentAttemptStatus.UNKNOWN, PaymentAttemptStatus.LEGACY_REVIEW, PaymentAttemptStatus.SUCCEEDED,
            PaymentAttemptStatus.PARTIALLY_REFUNDED, PaymentAttemptStatus.REFUNDED);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentReadinessService readinessService;
    private final Clock clock;

    public PaymentAttemptPersistenceService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentAttemptRepository attemptRepository,
            PaymentReadinessService readinessService,
            @Qualifier("paymentClock") Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.readinessService = readinessService;
        this.clock = clock;
    }

    @Transactional
    public PaymentAttemptCreationResult createOrReplay(
            Integer ownerId,
            CreatePaymentAttemptRequest request,
            String idempotencyKey,
            boolean providerAvailable) {
        if (ownerId == null || request == null || request.orderId() == null || request.method() == null) {
            throw badRequest("PAYMENT_REQUEST_INVALID", "Order and payment method are required");
        }
        Order order = orderRepository.findByIdForUpdate(request.orderId())
                .orElseThrow(() -> notFound("Order was not found"));
        if (order.getUser() == null || !Objects.equals(order.getUser().getId(), ownerId)) {
            throw notFound("Order was not found");
        }

        PaymentProvider provider = switch (request.method()) {
            case BANK_TRANSFER -> PaymentProvider.PAYOS;
            case VNPAY, MOMO -> throw badRequest("PAYMENT_METHOD_RETIRED", "Only BANK_TRANSFER is accepted for new payment attempts");
        };
        Long payableAmountVnd = order.getPayableAmountVnd();
        if (payableAmountVnd == null || payableAmountVnd <= 0) {
            throw conflict("PAYMENT_TOTAL_NOT_READY", "The Backend payable total is not confirmed and frozen");
        }
        String fingerprint = fingerprint(ownerId, order.getId(), provider, payableAmountVnd);

        var existingByKey = attemptRepository.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (existingByKey.isPresent()) {
            PaymentAttempt existing = existingByKey.get();
            if (!Objects.equals(existing.getOrderId(), order.getId()) || existing.getProvider() != provider
                    || !MessageDigest.isEqual(existing.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
                    fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used for a different request");
            }
            return new PaymentAttemptCreationResult(existing, false);
        }

        var baseReadiness = readinessService.evaluate(order, false);
        if (!baseReadiness.eligible()) {
            throw conflict(baseReadiness.blocker(), "Order is not ready for payment");
        }
        if (!providerAvailable) {
            throw new PaymentApiException(503, "PAYMENT_PROVIDER_UNAVAILABLE",
                    "The selected payment provider is not verified and available");
        }
        var attemptReadiness = readinessService.evaluate(order, true);
        if (!attemptReadiness.eligible()) {
            throw conflict(attemptReadiness.blocker(), "An unresolved payment state prevents another attempt");
        }
        if (attemptRepository.existsByOrderIdAndStatusIn(order.getId(), BLOCKING_STATUSES)) {
            throw conflict("PAYMENT_ATTEMPT_UNRESOLVED", "An unresolved payment attempt prevents another attempt");
        }

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        if (payment == null) {
            payment = Payment.builder().order(order).amount((double) payableAmountVnd)
                    .amountVnd(payableAmountVnd).method(request.method()).status(PaymentStatus.PENDING).build();
            payment = paymentRepository.save(payment);
        } else {
            if (attemptRepository.findByOrderIdOrderByCreatedAtDescAttemptIdDesc(order.getId()).isEmpty()) {
                throw conflict("LEGACY_PAYMENT_REVIEW", "Existing payment requires reconciliation before retry");
            }
            payment.setAmount((double) payableAmountVnd); // compatibility field only; amountVnd is authoritative.
            payment.setAmountVnd(payableAmountVnd);
            payment.setMethod(request.method());
            payment.setStatus(PaymentStatus.PENDING);
            payment = paymentRepository.save(payment);
        }

        Instant now = clock.instant();
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = PaymentAttempt.builder()
                .attemptId(attemptId)
                .paymentId(payment.getId())
                .orderId(order.getId())
                .ownerId(ownerId)
                .provider(provider)
                .amountVnd(payableAmountVnd)
                .status(PaymentAttemptStatus.INITIATED)
                .idempotencyKey(idempotencyKey)
                .requestFingerprint(fingerprint)
                .merchantReference(ProviderReference.forAttempt(provider, attemptId))
                .createdAt(now)
                .updatedAt(now)
                .build();
        return new PaymentAttemptCreationResult(attemptRepository.save(attempt), true);
    }

    @Transactional(readOnly = true)
    public PaymentAttempt getOwnedAttempt(Integer ownerId, UUID attemptId) {
        if (ownerId == null || attemptId == null) throw notFound("Payment attempt was not found");
        return attemptRepository.findByAttemptIdAndOwnerId(attemptId, ownerId)
                .orElseThrow(() -> notFound("Payment attempt was not found"));
    }

    @Transactional(readOnly = true)
    public PaymentAttempt getAttemptForLifecycle(UUID attemptId) {
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> notFound("Payment attempt was not found"));
    }

    public String requestFingerprint(Integer ownerId, Integer orderId, PaymentProvider provider, long amountVnd) {
        return fingerprint(ownerId, orderId, provider, amountVnd);
    }

    private String fingerprint(Integer ownerId, Integer orderId, PaymentProvider provider, long amountVnd) {
        String canonical = "payment-attempt:v1|" + ownerId + "|" + orderId + "|" + provider.name() + "|" + amountVnd;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private PaymentApiException badRequest(String code, String message) {
        return new PaymentApiException(400, code, message);
    }

    private PaymentApiException notFound(String message) {
        return new PaymentApiException(404, "PAYMENT_RESOURCE_NOT_FOUND", message);
    }

    private PaymentApiException conflict(String code, String message) {
        return new PaymentApiException(409, code == null ? "PAYMENT_NOT_READY" : code, message);
    }
}
