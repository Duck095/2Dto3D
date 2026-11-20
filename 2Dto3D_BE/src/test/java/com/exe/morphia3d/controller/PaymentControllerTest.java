package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.dto.response.PaymentAttemptResponse;
import com.exe.morphia3d.dto.response.PaymentReadinessResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.PaymentMethod;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.feature.FeatureDisabledException;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.feature.FeatureGateProperties;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.PaymentApiException;
import com.exe.morphia3d.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PaymentControllerTest {
    private PaymentService paymentService;
    private PaymentController controller;
    private Authentication authentication;
    private FeatureGate enabledGate;
    private final UUID attemptId = UUID.fromString("f5ae8520-5472-4bc6-a6d4-c99cc86092ee");

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        FeatureGateProperties enabled = new FeatureGateProperties();
        enabled.getPayment().setEnabled(true);
        enabledGate = new FeatureGate(enabled);
        controller = new PaymentController(paymentService, enabledGate);
        UserPrincipal principal = new UserPrincipal(User.builder().id(42).name("Buyer")
                .email("buyer@example.invalid").password("test-only").build());
        authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void readinessUsesAuthenticatedOwnerAndBackendOrderIdentity() {
        PaymentReadinessResponse expected = new PaymentReadinessResponse(41, true, null,
                125000L, 10000L, 135000L, Map.of(PaymentProvider.PAYOS, false));
        when(paymentService.getReadiness(42, 41)).thenReturn(expected);

        assertThat(controller.readiness(authentication, 41).getBody()).isSameAs(expected);
        verify(paymentService).getReadiness(42, 41);
    }

    @Test
    void attemptRequestPassesOnlyOrderProviderAndIdempotencyKey() {
        CreatePaymentAttemptRequest request = new CreatePaymentAttemptRequest(41, PaymentMethod.BANK_TRANSFER);
        PaymentAttemptResponse expected = new PaymentAttemptResponse(attemptId, 41, PaymentProvider.PAYOS,
                135000L, com.exe.morphia3d.domain.payment.PaymentAttemptStatus.INITIATED,
                false, null, null);
        when(paymentService.createAttempt(42, request, "key-1")).thenReturn(expected);

        assertThat(controller.createAttempt(authentication, request, "key-1").getBody()).isSameAs(expected);
        verify(paymentService).createAttempt(42, request, "key-1");
    }

    @Test
    void attemptLookupUsesAuthenticatedOwner() {
        PaymentAttemptResponse expected = new PaymentAttemptResponse(attemptId, 41, PaymentProvider.PAYOS,
                135000L, com.exe.morphia3d.domain.payment.PaymentAttemptStatus.UNKNOWN, false, null, null);
        when(paymentService.getAttempt(42, attemptId)).thenReturn(expected);

        assertThat(controller.getAttempt(authentication, attemptId).getBody()).isSameAs(expected);
        verify(paymentService).getAttempt(42, attemptId);
    }

    @Test
    void missingOrInvalidAuthenticationIs401AndDoesNotCallService() {
        assertThat(assertThrows(PaymentApiException.class, () -> controller.readiness(null, 41)).getStatus()).isEqualTo(401);
        Authentication invalid = new UsernamePasswordAuthenticationToken("not-a-user", null);
        assertThat(assertThrows(PaymentApiException.class, () -> controller.getAttempt(invalid, attemptId)).getStatus()).isEqualTo(401);
        verifyNoInteractions(paymentService);
    }

    @Test
    void disabledGateBlocksEveryPaymentRouteBeforeServiceCalls() {
        PaymentController disabled = new PaymentController(paymentService,
                new FeatureGate(new FeatureGateProperties()));
        assertThrows(FeatureDisabledException.class, () -> disabled.readiness(authentication, 41));
        assertThrows(FeatureDisabledException.class,
                () -> disabled.createAttempt(authentication, new CreatePaymentAttemptRequest(41, PaymentMethod.BANK_TRANSFER), "key-1"));
        assertThrows(FeatureDisabledException.class, () -> disabled.getAttempt(authentication, attemptId));
        verifyNoInteractions(paymentService);
    }
}
