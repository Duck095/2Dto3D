package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.dto.response.PaymentAttemptResponse;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.enums.PaymentMethod;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.service.PaymentApiException;
import com.exe.morphia3d.service.PaymentAttemptCreationResult;
import com.exe.morphia3d.service.PaymentAttemptGateway;
import com.exe.morphia3d.service.PaymentAttemptLifecycleService;
import com.exe.morphia3d.service.PaymentAttemptPersistenceService;
import com.exe.morphia3d.service.PaymentReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentServiceImplTest {
    private FeatureGate featureGate;
    private PaymentReadinessService readinessService;
    private PaymentAttemptPersistenceService persistenceService;
    private PaymentAttemptLifecycleService lifecycleService;
    private PaymentAttemptGateway gateway;
    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        featureGate = mock(FeatureGate.class);
        readinessService = mock(PaymentReadinessService.class);
        persistenceService = mock(PaymentAttemptPersistenceService.class);
        lifecycleService = mock(PaymentAttemptLifecycleService.class);
        gateway = mock(PaymentAttemptGateway.class);
        when(gateway.provider()).thenReturn(PaymentProvider.PAYOS);
        when(gateway.isAvailable()).thenReturn(true);
        service = new PaymentServiceImpl(featureGate, readinessService, persistenceService,
                lifecycleService, List.of(gateway));
    }

    @Test
    void createsAttemptFromOrderAndDispatchesOnlyOnceForTheFirstIdempotencyRequest() {
        PaymentAttempt attempt = attempt(PaymentAttemptStatus.INITIATED);
        PaymentAttemptResponse submitted = response(attempt, PaymentAttemptStatus.PENDING);
        when(persistenceService.createOrReplay(42, request(), "key-1", true))
                .thenReturn(new PaymentAttemptCreationResult(attempt, true));
        when(lifecycleService.dispatchNewAttempt(attempt.getAttemptId(), gateway)).thenReturn(submitted);

        PaymentAttemptResponse actual = service.createAttempt(42, request(), "key-1");

        assertThat(actual).isEqualTo(submitted);
        verify(persistenceService).createOrReplay(42, request(), "key-1", true);
        verify(lifecycleService).dispatchNewAttempt(attempt.getAttemptId(), gateway);
    }

    @Test
    void idempotentReplayReturnsExistingAttemptWithoutResubmittingToProvider() {
        PaymentAttempt attempt = attempt(PaymentAttemptStatus.UNKNOWN);
        PaymentAttemptResponse existing = response(attempt, PaymentAttemptStatus.UNKNOWN);
        when(persistenceService.createOrReplay(42, request(), "key-1", true))
                .thenReturn(new PaymentAttemptCreationResult(attempt, false));
        when(lifecycleService.response(attempt, null)).thenReturn(existing);

        PaymentAttemptResponse actual = service.createAttempt(42, request(), "key-1");

        assertThat(actual).isEqualTo(existing);
        verify(lifecycleService, never()).dispatchNewAttempt(any(), any());
    }

    @Test
    void unavailableProviderCannotCreateOrDispatchAnAttempt() {
        when(gateway.isAvailable()).thenReturn(false);
        when(persistenceService.createOrReplay(42, request(), "key-1", false))
                .thenThrow(new PaymentApiException(503, "PAYMENT_PROVIDER_UNAVAILABLE", "Provider unavailable"));

        assertThatThrownBy(() -> service.createAttempt(42, request(), "key-1"))
                .isInstanceOf(PaymentApiException.class)
                .extracting("status").isEqualTo(503);
        verify(lifecycleService, never()).dispatchNewAttempt(any(), any());
    }

    @Test
    void rejectsMissingIdempotencyKeyBeforePersistence() {
        assertThatThrownBy(() -> service.createAttempt(42, request(), " "))
                .isInstanceOf(PaymentApiException.class)
                .extracting("status").isEqualTo(400);
        verifyNoInteractions(persistenceService, lifecycleService);
    }

    @Test
    void bankTransferMapsOnlyToPayosAndLegacyProvidersAreRejectedForNewAttempts() {
        when(gateway.provider()).thenReturn(PaymentProvider.PAYOS);
        CreatePaymentAttemptRequest bankTransfer = new CreatePaymentAttemptRequest(41, PaymentMethod.BANK_TRANSFER);
        PaymentAttempt attempt = attempt(PaymentAttemptStatus.INITIATED);
        when(persistenceService.createOrReplay(42, bankTransfer, "key-1", true))
                .thenReturn(new PaymentAttemptCreationResult(attempt, true));
        when(lifecycleService.dispatchNewAttempt(attempt.getAttemptId(), gateway)).thenReturn(response(attempt, PaymentAttemptStatus.PENDING));

        service.createAttempt(42, bankTransfer, "key-1");

        verify(persistenceService).createOrReplay(42, bankTransfer, "key-1", true);
        for (PaymentMethod retired : List.of(PaymentMethod.VNPAY, PaymentMethod.MOMO)) {
            assertThatThrownBy(() -> service.createAttempt(42, new CreatePaymentAttemptRequest(41, retired), "key-2"))
                    .isInstanceOf(PaymentApiException.class).extracting("status").isEqualTo(400);
        }
    }

    private CreatePaymentAttemptRequest request() {
        return new CreatePaymentAttemptRequest(41, PaymentMethod.BANK_TRANSFER);
    }

    private PaymentAttempt attempt(PaymentAttemptStatus status) {
        return PaymentAttempt.builder().attemptId(UUID.fromString("f5ae8520-5472-4bc6-a6d4-c99cc86092ee"))
                .orderId(41).ownerId(42).provider(PaymentProvider.PAYOS).amountVnd(125000L)
                .status(status).idempotencyKey("key-1").requestFingerprint("f".repeat(64)).build();
    }

    private PaymentAttemptResponse response(PaymentAttempt attempt, PaymentAttemptStatus status) {
        return new PaymentAttemptResponse(attempt.getAttemptId(), attempt.getOrderId(), attempt.getProvider(),
                attempt.getAmountVnd(), status, false, null, null);
    }
}
