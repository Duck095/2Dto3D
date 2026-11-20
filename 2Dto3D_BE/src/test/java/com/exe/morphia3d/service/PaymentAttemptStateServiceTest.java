package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptEvent;
import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.domain.payment.PaymentAttemptTransitionPolicy;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.Payment;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.entity.PaymentCallbackEvent;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.enums.PaymentStatus;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.PaymentCallbackEventRepository;
import com.exe.morphia3d.repository.PaymentReconciliationCaseRepository;
import com.exe.morphia3d.repository.PaymentRepository;
import com.exe.morphia3d.service.payment.provider.ProviderReference;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class PaymentAttemptStateServiceTest {
    @Test
    void submissionClaimPersistsStableProviderRequestIdentityBeforeAnyProviderCall() {
        UUID attemptId = UUID.randomUUID();
        Order order = Order.builder().id(7).status(OrderStatus.PENDING).build();
        PaymentAttempt attempt = PaymentAttempt.builder().attemptId(attemptId).paymentId(19).orderId(7)
                .provider(PaymentProvider.MOMO).amountVnd(135000L).merchantReference(ProviderReference.forAttempt(PaymentProvider.MOMO, attemptId))
                .status(PaymentAttemptStatus.INITIATED).build();
        OrderRepository orders = mock(OrderRepository.class);
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(orders.findByIdForUpdate(7)).thenReturn(Optional.of(order));
        when(attempts.findOrderAttemptsForUpdate(eq(7), anyCollection())).thenReturn(List.of(attempt));
        when(attempts.save(any(PaymentAttempt.class))).thenAnswer(call -> call.getArgument(0));
        PaymentAttemptStateService service = new PaymentAttemptStateService(orders, mock(PaymentRepository.class), attempts, mock(EntityManager.class),
                mock(PaymentCallbackEventRepository.class), mock(PaymentReconciliationCaseRepository.class),
                new PaymentAttemptTransitionPolicy(), Clock.systemUTC());

        assertThat(service.claimSubmission(attemptId)).isTrue();
        assertThat(attempt.getProviderRequestId()).isEqualTo(attempt.getMerchantReference());
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUBMITTING);
    }

    @Test
    void verifiedSuccessIsAppliedOnceAndDuplicateReceiptDoesNotUpdateOrderAgain() {
        UUID attemptId = UUID.randomUUID();
        Order order = Order.builder().id(7).status(OrderStatus.PENDING).build();
        Payment payment = Payment.builder().id(19).amountVnd(135000L).status(PaymentStatus.PENDING).build();
        PaymentAttempt attempt = PaymentAttempt.builder().attemptId(attemptId).paymentId(19).orderId(7)
                .provider(PaymentProvider.VNPAY).amountVnd(135000L).merchantReference("merchant-7")
                .status(PaymentAttemptStatus.SUBMITTING).build();
        PaymentCallbackEvent savedReceipt = PaymentCallbackEvent.builder().provider(PaymentProvider.VNPAY)
                .providerEventKey("evt-1").attemptId(attemptId).eventDigest("a".repeat(64)).build();

        OrderRepository orders = mock(OrderRepository.class);
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentCallbackEventRepository callbacks = mock(PaymentCallbackEventRepository.class);
        PaymentReconciliationCaseRepository reconciliations = mock(PaymentReconciliationCaseRepository.class);
        when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(orders.findByIdForUpdate(7)).thenReturn(Optional.of(order));
        when(attempts.findOrderAttemptsForUpdate(eq(7), anyCollection())).thenReturn(List.of(attempt));
        when(payments.findByOrderId(7)).thenReturn(Optional.of(payment));
        when(callbacks.findByProviderAndProviderEventKey(PaymentProvider.VNPAY, "evt-1"))
                .thenReturn(Optional.empty(), Optional.of(savedReceipt));
        when(callbacks.save(any(PaymentCallbackEvent.class))).thenAnswer(call -> call.getArgument(0));
        when(attempts.save(any(PaymentAttempt.class))).thenAnswer(call -> call.getArgument(0));
        when(payments.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
        when(reconciliations.existsByOrderIdAndResolutionState(7, "OPEN")).thenReturn(false);

        PaymentAttemptStateService service = new PaymentAttemptStateService(orders, payments, attempts, mock(EntityManager.class), callbacks,
                reconciliations, new PaymentAttemptTransitionPolicy(),
                Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC));
        VerifiedPaymentEvent success = new VerifiedPaymentEvent(PaymentProvider.VNPAY, attemptId, "merchant-7",
                "evt-1", "a".repeat(64), 135000L, "txn-1", PaymentAttemptEvent.SUCCESS,
                Instant.parse("2026-11-12T09:59:00Z"), "TEST_ONLY_VERIFIER");

        assertThat(service.applyVerifiedEvent(success).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(service.applyVerifiedEvent(success).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(callbacks, times(1)).save(any(PaymentCallbackEvent.class));
        verify(orders, times(1)).save(order);
        verify(payments, times(1)).save(payment);
    }

    @Test
    void acceptedSubmissionAfterIpnSuccessCannotDowngradeSucceededAttempt() {
        UUID attemptId = UUID.randomUUID();
        Order order = Order.builder().id(7).status(OrderStatus.PROCESSING).build();
        PaymentAttempt succeeded = PaymentAttempt.builder().attemptId(attemptId).paymentId(19).orderId(7)
                .provider(PaymentProvider.MOMO).amountVnd(50000L).status(PaymentAttemptStatus.SUCCEEDED).build();
        OrderRepository orders = mock(OrderRepository.class);
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        when(attempts.findById(attemptId)).thenReturn(Optional.of(succeeded));
        when(orders.findByIdForUpdate(7)).thenReturn(Optional.of(order));
        when(attempts.findOrderAttemptsForUpdate(eq(7), anyCollection())).thenReturn(List.of(succeeded));

        PaymentAttemptStateService service = new PaymentAttemptStateService(orders, mock(PaymentRepository.class), attempts, mock(EntityManager.class),
                mock(PaymentCallbackEventRepository.class), mock(PaymentReconciliationCaseRepository.class),
                new PaymentAttemptTransitionPolicy(), Clock.systemUTC());
        PaymentAttempt result = service.recordSubmission(attemptId,
                new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.ACCEPTED,
                        "request-1", null, null, null, null));

        assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        verify(attempts, never()).save(any());
    }
}
