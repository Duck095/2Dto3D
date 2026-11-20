package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptDecision;
import com.exe.morphia3d.domain.payment.PaymentAttemptDisposition;
import com.exe.morphia3d.domain.payment.PaymentAttemptEvent;
import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.domain.payment.PaymentAttemptTransitionPolicy;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.Payment;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.entity.PaymentCallbackEvent;
import com.exe.morphia3d.entity.PaymentReconciliationCase;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.enums.PaymentStatus;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.PaymentCallbackEventRepository;
import com.exe.morphia3d.repository.PaymentReconciliationCaseRepository;
import com.exe.morphia3d.repository.PaymentRepository;
import com.exe.morphia3d.service.payment.provider.ProviderReference;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PaymentAttemptStateService {
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final EntityManager entityManager;
    private final PaymentCallbackEventRepository callbackRepository;
    private final PaymentReconciliationCaseRepository reconciliationRepository;
    private final PaymentAttemptTransitionPolicy transitionPolicy;
    private final Clock clock;

    public PaymentAttemptStateService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentAttemptRepository attemptRepository,
            EntityManager entityManager,
            PaymentCallbackEventRepository callbackRepository,
            PaymentReconciliationCaseRepository reconciliationRepository,
            PaymentAttemptTransitionPolicy transitionPolicy,
            @Qualifier("paymentClock") Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.entityManager = entityManager;
        this.callbackRepository = callbackRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.transitionPolicy = transitionPolicy;
        this.clock = clock;
    }

    @Transactional
    public boolean claimSubmission(UUID attemptId) {
        LockedAttempt locked = lock(attemptId);
        if (!ProviderReference.forAttempt(locked.attempt().getProvider(), attemptId)
                .equals(locked.attempt().getMerchantReference())) {
            throw conflict("PROVIDER_REFERENCE_REQUIRES_REVIEW", "Stored provider reference is not safe for this adapter");
        }
        PaymentAttemptDecision decision = transitionPolicy.decide(locked.attempt().getStatus(),
                PaymentAttemptEvent.SUBMISSION_CLAIMED, locked.order().getStatus());
        if (decision.disposition() != PaymentAttemptDisposition.APPLIED) return false;
        Instant now = clock.instant();
        locked.attempt().setStatus(decision.status());
        if (locked.attempt().getProviderRequestId() == null) {
            locked.attempt().setProviderRequestId(locked.attempt().getMerchantReference());
        } else if (!Objects.equals(locked.attempt().getProviderRequestId(), locked.attempt().getMerchantReference())) {
            throw conflict("PROVIDER_REQUEST_ID_MISMATCH", "Stored provider request identity does not match this attempt");
        }
        locked.attempt().setSubmittedAt(now);
        locked.attempt().setUpdatedAt(now);
        attemptRepository.save(locked.attempt());
        return true;
    }

    @Transactional
    public PaymentAttempt recordSubmission(UUID attemptId, PaymentProviderSubmissionResult result) {
        LockedAttempt locked = lock(attemptId);
        PaymentAttempt attempt = locked.attempt();
        if (result == null) return markUnknown(locked, "PROVIDER_RESULT_MISSING");
        Instant now = clock.instant();
        PaymentProviderSubmissionResult.Outcome outcome = result.outcome();
        if (outcome == null) return markUnknown(locked, "PROVIDER_RESULT_UNKNOWN");
        PaymentAttemptEvent event = switch (outcome) {
            case ACCEPTED -> PaymentAttemptEvent.PROVIDER_ACCEPTED;
            case REJECTED -> PaymentAttemptEvent.FAILURE;
            case UNKNOWN -> PaymentAttemptEvent.AMBIGUOUS_RESULT;
        };
        if (outcome == PaymentProviderSubmissionResult.Outcome.ACCEPTED
                && !validProviderReference(result.providerRequestId())) {
            return markUnknown(locked, "PROVIDER_REFERENCE_MISSING");
        }
        PaymentAttemptDecision decision = transitionPolicy.decide(attempt.getStatus(), event, locked.order().getStatus());
        if (decision.disposition() == PaymentAttemptDisposition.APPLIED) {
            attempt.setStatus(decision.status());
            attempt.setUpdatedAt(now);
            if (event == PaymentAttemptEvent.PROVIDER_ACCEPTED) {
                attempt.setProviderRequestId(result.providerRequestId());
                if (validProviderReference(result.providerTransactionId())) {
                    attempt.setProviderTransactionId(result.providerTransactionId());
                }
            } else if (event == PaymentAttemptEvent.AMBIGUOUS_RESULT) {
                attempt.setUnknownSince(now);
                attempt.setErrorCode(safeCode(result.errorCode(), "PROVIDER_RESULT_UNKNOWN"));
                attempt.setErrorMessage(safeMessage(result.safeErrorMessage()));
            } else {
                attempt.setResolvedAt(now);
                attempt.setErrorCode(safeCode(result.errorCode(), "PROVIDER_REJECTED"));
                attempt.setErrorMessage(safeMessage(result.safeErrorMessage()));
                setPaymentStatus(locked.order(), PaymentStatus.FAILED, now);
            }
            return attemptRepository.save(attempt);
        }
        return attempt;
    }

    @Transactional
    public PaymentAttempt markSubmissionUnknown(UUID attemptId, String errorCode, String safeMessage) {
        LockedAttempt locked = lock(attemptId);
        PaymentAttemptDecision decision = transitionPolicy.decide(locked.attempt().getStatus(),
                PaymentAttemptEvent.AMBIGUOUS_RESULT, locked.order().getStatus());
        if (decision.disposition() != PaymentAttemptDisposition.APPLIED) return locked.attempt();
        Instant now = clock.instant();
        locked.attempt().setStatus(decision.status());
        locked.attempt().setUnknownSince(now);
        locked.attempt().setUpdatedAt(now);
        locked.attempt().setErrorCode(safeCode(errorCode, "PROVIDER_RESULT_UNKNOWN"));
        locked.attempt().setErrorMessage(safeMessage(safeMessage));
        return attemptRepository.save(locked.attempt());
    }

    @Transactional
    public PaymentAttempt applyVerifiedEvent(VerifiedPaymentEvent event) {
        validateVerifiedEvent(event);
        LockedAttempt locked = lock(event.attemptId());
        PaymentAttempt attempt = locked.attempt();
        if (event.provider() != attempt.getProvider()
                || !Objects.equals(event.merchantReference(), attempt.getMerchantReference())) {
            throw conflict("CALLBACK_REFERENCE_MISMATCH", "Verified event does not match the payment attempt");
        }

        PaymentCallbackEvent duplicate = callbackRepository.findByProviderAndProviderEventKey(
                event.provider(), event.providerEventKey()).orElse(null);
        if (duplicate != null) {
            if (Objects.equals(duplicate.getAttemptId(), attempt.getAttemptId())
                    && Objects.equals(duplicate.getEventDigest(), event.eventDigest())) return attempt;
            throw conflict("CALLBACK_EVENT_KEY_REUSED", "Provider event key was already used for different evidence");
        }
        Instant receivedAt = clock.instant();
        callbackRepository.save(PaymentCallbackEvent.builder()
                .provider(event.provider()).providerEventKey(event.providerEventKey())
                .attemptId(attempt.getAttemptId()).eventDigest(event.eventDigest())
                .verifiedAt(event.verifiedAt()).receivedAt(receivedAt).build());

        Payment aggregate = paymentRepository.findByOrderId(locked.order().getId()).orElse(null);
        if (aggregate == null || aggregate.getAmountVnd() == null
                || !Objects.equals(aggregate.getId(), attempt.getPaymentId())
                || !Objects.equals(aggregate.getAmountVnd(), attempt.getAmountVnd())) {
            openReconciliation(locked, event, "Payment aggregate does not match the frozen attempt amount");
            return attempt;
        }
        if (event.amountVnd() != attempt.getAmountVnd()
                || transactionReferenceConflicts(attempt, event.providerTransactionId())) {
            openReconciliation(locked, event, event.amountVnd() != attempt.getAmountVnd()
                    ? "Verified provider amount does not match the frozen attempt amount"
                    : "Provider transaction reference conflicts with prior verified evidence");
            return attempt;
        }

        PaymentAttemptDecision decision = transitionPolicy.decide(attempt.getStatus(), event.event(),
                locked.order().getStatus());
        attempt.setLastCallbackAt(receivedAt);
        if (decision.disposition() == PaymentAttemptDisposition.APPLIED) {
            attempt.setStatus(decision.status());
            attempt.setUpdatedAt(receivedAt);
            if (validProviderReference(event.providerTransactionId())) {
                attempt.setProviderTransactionId(event.providerTransactionId());
            }
            if (isResolved(decision.status())) attempt.setResolvedAt(receivedAt);
            if (decision.status() == PaymentAttemptStatus.SUCCEEDED) {
                setPaymentStatus(locked.order(), PaymentStatus.SUCCESS, receivedAt);
                if (decision.advanceOrderToProcessing()) {
                    locked.order().setStatus(OrderStatus.PROCESSING);
                    orderRepository.save(locked.order());
                }
            } else if (decision.status() == PaymentAttemptStatus.FAILED) {
                setPaymentStatus(locked.order(), PaymentStatus.FAILED, receivedAt);
            } else if (decision.status() == PaymentAttemptStatus.CANCELED
                    || decision.status() == PaymentAttemptStatus.EXPIRED) {
                setPaymentStatus(locked.order(), PaymentStatus.FAILED, receivedAt);
            }
        }
        if (decision.disposition() == PaymentAttemptDisposition.RECONCILIATION_REQUIRED) {
            if (decision.status() == PaymentAttemptStatus.SUCCEEDED) {
                attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
                attempt.setResolvedAt(receivedAt);
                attempt.setUpdatedAt(receivedAt);
                setPaymentStatus(locked.order(), PaymentStatus.SUCCESS, receivedAt);
            }
            openReconciliation(locked, event,
                    "Verified success conflicts with the attempt or Order state");
        }
        return attemptRepository.save(attempt);
    }

    @Transactional(readOnly = true)
    public PaymentAttempt get(UUID attemptId) {
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> new PaymentApiException(404, "PAYMENT_ATTEMPT_NOT_FOUND", "Payment attempt was not found"));
    }

    private PaymentAttempt markUnknown(LockedAttempt locked, String code) {
        PaymentAttemptDecision decision = transitionPolicy.decide(locked.attempt().getStatus(),
                PaymentAttemptEvent.AMBIGUOUS_RESULT, locked.order().getStatus());
        if (decision.disposition() != PaymentAttemptDisposition.APPLIED) return locked.attempt();
        Instant now = clock.instant();
        locked.attempt().setStatus(decision.status());
        locked.attempt().setUnknownSince(now);
        locked.attempt().setUpdatedAt(now);
        locked.attempt().setErrorCode(safeCode(code, "PROVIDER_RESULT_UNKNOWN"));
        return attemptRepository.save(locked.attempt());
    }

    private LockedAttempt lock(UUID attemptId) {
        PaymentAttempt observed = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new PaymentApiException(404, "PAYMENT_ATTEMPT_NOT_FOUND", "Payment attempt was not found"));
        Order order = orderRepository.findByIdForUpdate(observed.getOrderId())
                .orElseThrow(() -> new PaymentApiException(404, "PAYMENT_ORDER_NOT_FOUND", "Order was not found"));
        List<PaymentAttempt> lockedAttempts = attemptRepository.findOrderAttemptsForUpdate(order.getId(),
                Arrays.asList(PaymentAttemptStatus.values()));
        PaymentAttempt attempt = lockedAttempts.stream()
                .filter(value -> value.getAttemptId().equals(attemptId)).findFirst()
                .orElseThrow(() -> new PaymentApiException(404, "PAYMENT_ATTEMPT_NOT_FOUND", "Payment attempt was not found"));
        // The attempt was read before waiting on the Order lock. Refresh it after acquiring the row lock
        // so this persistence context cannot apply a callback against a stale pre-lock state.
        entityManager.refresh(attempt, LockModeType.PESSIMISTIC_WRITE);
        return new LockedAttempt(order, attempt);
    }

    private void setPaymentStatus(Order order, PaymentStatus status, Instant at) {
        Payment payment = paymentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new PaymentApiException(409, "PAYMENT_AGGREGATE_MISSING", "Payment aggregate is missing"));
        payment.setStatus(status);
        if (status == PaymentStatus.SUCCESS && payment.getPaidAt() == null) {
            payment.setPaidAt(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        }
        paymentRepository.save(payment);
    }

    private void openReconciliation(LockedAttempt locked, VerifiedPaymentEvent event, String reason) {
        if (reconciliationRepository.existsByOrderIdAndResolutionState(locked.order().getId(), "OPEN")) return;
        reconciliationRepository.save(PaymentReconciliationCase.builder()
                .caseId(UUID.randomUUID()).attemptId(locked.attempt().getAttemptId())
                .orderId(locked.order().getId()).provider(locked.attempt().getProvider())
                .providerEventKey(event.providerEventKey()).eventDigest(event.eventDigest())
                .observedAmountVnd(Math.max(1L, event.amountVnd()))
                .providerTransactionId(bound(event.providerTransactionId(), 255))
                .verificationSource(bound(event.verificationSource(), 80))
                .verifiedAt(event.verifiedAt()).conflictReason(bound(reason, 500))
                .resolutionState("OPEN").createdAt(clock.instant()).build());
    }

    private boolean transactionReferenceConflicts(PaymentAttempt attempt, String incoming) {
        return validProviderReference(attempt.getProviderTransactionId())
                && validProviderReference(incoming)
                && !Objects.equals(attempt.getProviderTransactionId(), incoming);
    }

    private void validateVerifiedEvent(VerifiedPaymentEvent event) {
        if (event == null || event.provider() == null || event.provider() == PaymentProvider.UNKNOWN
                || event.attemptId() == null || blankOrTooLong(event.merchantReference(), 255)
                || blankOrTooLong(event.providerEventKey(), 255)
                || event.eventDigest() == null || !SHA256.matcher(event.eventDigest()).matches()
                || event.amountVnd() <= 0 || event.event() == null || event.verifiedAt() == null
                || blankOrTooLong(event.verificationSource(), 80)) {
            throw badRequest("VERIFIED_CALLBACK_INVALID", "Verified callback evidence is incomplete");
        }
        if (event.event() != PaymentAttemptEvent.SUCCESS && event.event() != PaymentAttemptEvent.FAILURE
                && event.event() != PaymentAttemptEvent.CANCEL && event.event() != PaymentAttemptEvent.EXPIRE) {
            throw badRequest("VERIFIED_CALLBACK_INVALID", "Callback event is not a provider terminal result");
        }
    }

    private boolean validProviderReference(String value) {
        return value != null && !value.isBlank() && value.length() <= 255;
    }

    private boolean blankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isResolved(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.SUCCEEDED || status == PaymentAttemptStatus.FAILED
                || status == PaymentAttemptStatus.CANCELED || status == PaymentAttemptStatus.EXPIRED;
    }

    private String safeCode(String value, String fallback) {
        return value == null || !value.matches("[A-Z0-9_]{1,64}") ? fallback : value;
    }

    private String safeMessage(String value) { return bound(value, 500); }
    private String bound(String value, int max) {
        if (value == null) return null;
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private PaymentApiException badRequest(String code, String message) { return new PaymentApiException(400, code, message); }
    private PaymentApiException conflict(String code, String message) { return new PaymentApiException(409, code, message); }
    private record LockedAttempt(Order order, PaymentAttempt attempt) {}
}
