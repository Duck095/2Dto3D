package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.dto.response.PaymentReadinessResponse;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.Payment;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.PaymentMethod;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentAttemptPersistenceServiceTest {
    private static final long EXACT_VND = 9_007_199_254_740_993L;
    private OrderRepository orders;
    private PaymentRepository payments;
    private PaymentAttemptRepository attempts;
    private PaymentReadinessService readiness;
    private PaymentAttemptPersistenceService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        payments = mock(PaymentRepository.class);
        attempts = mock(PaymentAttemptRepository.class);
        readiness = mock(PaymentReadinessService.class);
        service = new PaymentAttemptPersistenceService(orders, payments, attempts, readiness,
                Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createsWithExactBackendVndAndDoesNotUseDoubleForAttemptAmount() {
        Order order = payableOrder();
        when(orders.findByIdForUpdate(7)).thenReturn(Optional.of(order));
        when(attempts.findByOwnerIdAndIdempotencyKey(42, "request-1")).thenReturn(Optional.empty());
        when(readiness.evaluate(order, false)).thenReturn(eligible());
        when(readiness.evaluate(order, true)).thenReturn(eligible());
        when(attempts.existsByOrderIdAndStatusIn(eq(7), any())).thenReturn(false);
        when(attempts.findByOrderIdOrderByCreatedAtDescAttemptIdDesc(7)).thenReturn(java.util.List.of());
        when(payments.findByOrderId(7)).thenReturn(Optional.empty());
        Payment payment = Payment.builder().id(19).order(order).build();
        when(payments.save(any(Payment.class))).thenReturn(payment);
        when(attempts.save(any(PaymentAttempt.class))).thenAnswer(call -> call.getArgument(0));

        PaymentAttemptCreationResult result = service.createOrReplay(42,
                new CreatePaymentAttemptRequest(7, PaymentMethod.BANK_TRANSFER), "request-1", true);

        assertThat(result.created()).isTrue();
        assertThat(result.attempt().getAmountVnd()).isEqualTo(EXACT_VND);
        assertThat(result.attempt().getStatus()).isEqualTo(PaymentAttemptStatus.INITIATED);
        assertThat(result.attempt().getRequestFingerprint()).hasSize(64);
        assertThat(result.attempt().getMerchantReference()).matches("[A-Za-z0-9]{32}");
        assertThat(result.attempt().getMerchantReference()).isEqualTo(
                result.attempt().getAttemptId().toString().replace("-", ""));
        verify(payments).save(argThat(saved -> saved.getAmountVnd().equals(EXACT_VND)));
    }

    @Test
    void replaysSameKeyAndFingerprintEvenWhenProviderAvailabilityHasSinceChanged() {
        Order order = payableOrder();
        PaymentAttempt prior = PaymentAttempt.builder().attemptId(UUID.randomUUID()).orderId(7).ownerId(42)
                .provider(PaymentProvider.PAYOS).amountVnd(EXACT_VND).status(PaymentAttemptStatus.UNKNOWN)
                .requestFingerprint(service.requestFingerprint(42, 7, PaymentProvider.PAYOS, EXACT_VND)).build();
        when(orders.findByIdForUpdate(7)).thenReturn(Optional.of(order));
        when(attempts.findByOwnerIdAndIdempotencyKey(42, "same-key")).thenReturn(Optional.of(prior));

        PaymentAttemptCreationResult result = service.createOrReplay(42,
                new CreatePaymentAttemptRequest(7, PaymentMethod.BANK_TRANSFER), "same-key", false);

        assertThat(result.created()).isFalse();
        assertThat(result.attempt()).isSameAs(prior);
        verifyNoInteractions(readiness, payments);
        verify(attempts, never()).save(any());
    }

    @Test
    void hidesForeignOrderAsNotFoundAndRejectsUnfrozenPayableTotal() {
        Order foreign = payableOrder();
        foreign.setUser(User.builder().id(99).build());
        when(orders.findByIdForUpdate(7)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.createOrReplay(42,
                new CreatePaymentAttemptRequest(7, PaymentMethod.BANK_TRANSFER), "key", true))
                .isInstanceOf(PaymentApiException.class).extracting("status").isEqualTo(404);

        Order unfrozen = payableOrder();
        unfrozen.setPayableAmountVnd(null);
        when(orders.findByIdForUpdate(8)).thenReturn(Optional.of(unfrozen));
        assertThatThrownBy(() -> service.createOrReplay(42,
                new CreatePaymentAttemptRequest(8, PaymentMethod.BANK_TRANSFER), "key", true))
                .isInstanceOf(PaymentApiException.class).extracting("status").isEqualTo(409);
        verifyNoInteractions(readiness, payments);
    }

    private Order payableOrder() {
        return Order.builder().id(7).user(User.builder().id(42).build())
                .totalAmountVnd(EXACT_VND).shippingAmountVnd(0L)
                .shippingConfirmationReference("confirmed-by-backoffice")
                .payableAmountVnd(EXACT_VND).paymentTotalFrozenAt(Instant.parse("2026-11-11T10:00:00Z")).build();
    }

    private PaymentReadinessResponse eligible() {
        return new PaymentReadinessResponse(7, true, null, EXACT_VND, 0L, EXACT_VND,
                Map.of(PaymentProvider.PAYOS, true));
    }
}
