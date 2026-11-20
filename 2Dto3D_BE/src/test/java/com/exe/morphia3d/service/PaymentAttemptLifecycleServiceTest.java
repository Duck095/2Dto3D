package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.enums.PaymentProvider;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentAttemptLifecycleServiceTest {
    @Test
    void ambiguousProviderFailureBecomesUnknownAndIsNeverAutomaticallyResubmitted() {
        UUID id = UUID.randomUUID();
        PaymentAttempt submitting = attempt(id, PaymentAttemptStatus.SUBMITTING);
        PaymentAttempt unknown = attempt(id, PaymentAttemptStatus.UNKNOWN);
        PaymentAttemptStateService states = mock(PaymentAttemptStateService.class);
        PaymentAttemptGateway gateway = mock(PaymentAttemptGateway.class);
        when(gateway.isAvailable()).thenReturn(true);
        when(states.claimSubmission(id)).thenReturn(true);
        when(states.get(id)).thenReturn(submitting);
        when(gateway.submit(any())).thenThrow(new IllegalStateException("connection lost"));
        when(states.markSubmissionUnknown(id, "PROVIDER_RESULT_UNKNOWN",
                "Provider response could not be durably confirmed")).thenReturn(unknown);
        PaymentAttemptLifecycleService service = new PaymentAttemptLifecycleService(states);

        assertThat(service.dispatchNewAttempt(id, gateway).status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        when(states.claimSubmission(id)).thenReturn(false);
        when(states.get(id)).thenReturn(unknown);
        assertThat(service.recoverUndispatchedAttempt(id, gateway).status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);

        verify(gateway, times(1)).submit(any());
        verify(states, times(2)).claimSubmission(id);
    }

    @Test
    void unavailableProviderFailsClosedWithoutClaimingAttempt() {
        UUID id = UUID.randomUUID();
        PaymentAttemptStateService states = mock(PaymentAttemptStateService.class);
        PaymentAttemptGateway gateway = mock(PaymentAttemptGateway.class);
        when(gateway.isAvailable()).thenReturn(false);
        PaymentAttemptLifecycleService service = new PaymentAttemptLifecycleService(states);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.dispatchNewAttempt(id, gateway))
                .isInstanceOf(PaymentApiException.class).extracting("status").isEqualTo(503);
        verifyNoInteractions(states);
        verify(gateway).isAvailable();
        verifyNoMoreInteractions(gateway);
    }

    private PaymentAttempt attempt(UUID id, PaymentAttemptStatus status) {
        return PaymentAttempt.builder().attemptId(id).orderId(7).provider(PaymentProvider.MOMO)
                .amountVnd(10000L).merchantReference("merchant-7").idempotencyKey("key-1").status(status).build();
    }
}
