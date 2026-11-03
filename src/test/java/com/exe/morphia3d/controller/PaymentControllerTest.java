package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.CreatePaymentRequest;
import com.exe.morphia3d.dto.response.PaymentUrlResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.PaymentMethod;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentControllerTest {

    private PaymentService paymentService;
    private PaymentController paymentController;
    private CreatePaymentRequest paymentRequest;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        paymentController = new PaymentController(paymentService);
        paymentRequest = new CreatePaymentRequest();
        paymentRequest.setOrderId(8);
        paymentRequest.setMethod(PaymentMethod.VNPAY);
        httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");
    }

    @Test
    void authenticatedPrincipalIdIsPassedExplicitlyToPaymentService() {
        Authentication authentication = authenticationFor(user(42));
        PaymentUrlResponse expected = PaymentUrlResponse.builder()
                .paymentId(9)
                .paymentUrl("https://pay.invalid/8")
                .build();
        when(paymentService.createPayment(42, paymentRequest, "127.0.0.1")).thenReturn(expected);

        PaymentUrlResponse actual = paymentController
                .createPayment(authentication, paymentRequest, httpRequest)
                .getBody();

        assertThat(actual).isSameAs(expected);
        verify(paymentService).createPayment(42, paymentRequest, "127.0.0.1");
    }

    @Test
    void anonymousRequestIsDeniedBeforeServiceInvocation() {
        assertThrows(AccessDeniedException.class,
                () -> paymentController.createPayment(null, paymentRequest, httpRequest));

        verifyNoInteractions(paymentService);
    }

    @Test
    void invalidAuthenticatedPrincipalIsDeniedBeforeServiceInvocation() {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken("not-a-user-principal", null);

        assertThrows(AccessDeniedException.class,
                () -> paymentController.createPayment(authentication, paymentRequest, httpRequest));

        verifyNoInteractions(paymentService);
    }

    @Test
    void idLessAuthenticatedPrincipalIsDeniedBeforeServiceInvocation() {
        Authentication authentication = authenticationFor(user(null));

        assertThrows(AccessDeniedException.class,
                () -> paymentController.createPayment(authentication, paymentRequest, httpRequest));

        verifyNoInteractions(paymentService);
    }

    private Authentication authenticationFor(User user) {
        UserPrincipal principal = new UserPrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private User user(Integer id) {
        return User.builder()
                .id(id)
                .name("User")
                .email("user@example.com")
                .password("encoded")
                .build();
    }
}
