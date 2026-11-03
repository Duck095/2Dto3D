package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.request.CreatePaymentRequest;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.Payment;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.enums.PaymentMethod;
import com.exe.morphia3d.enums.PaymentStatus;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentRepository;
import com.exe.morphia3d.service.payment.PaymentGatewayService;
import jakarta.persistence.EnumType;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.Enumerated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceImplTest {

    private PaymentRepository paymentRepository;
    private OrderRepository orderRepository;
    private PaymentGatewayService vnPayService;
    private PaymentGatewayService momoService;
    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        orderRepository = mock(OrderRepository.class);
        vnPayService = mock(PaymentGatewayService.class);
        momoService = mock(PaymentGatewayService.class);
        paymentService = new PaymentServiceImpl(paymentRepository, orderRepository, vnPayService, momoService);
    }

    @Test
    void ownerCanCreatePaymentForPendingOrderUsingServerDerivedAmount() {
        Order order = pendingOrder(user(42), 125.0);
        CreatePaymentRequest request = paymentRequest(8, PaymentMethod.VNPAY);
        when(orderRepository.findById(8)).thenReturn(Optional.of(order));
        when(paymentRepository.save(any())).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(9);
            return payment;
        });
        when(vnPayService.createPaymentUrl(8, 125.0, "127.0.0.1")).thenReturn("https://pay.invalid/8");

        paymentService.createPayment(42, request, "127.0.0.1");

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getOrder()).isSameAs(order);
        assertThat(paymentCaptor.getValue().getAmount()).isEqualTo(125.0);
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(vnPayService).createPaymentUrl(8, 125.0, "127.0.0.1");
        verify(momoService, never()).createPaymentUrl(any(), any(), any());
    }

    @Test
    void foreignAdminCannotCreatePaymentOrInvokeGateway() {
        Order order = pendingOrder(user(42), 125.0);
        CreatePaymentRequest request = paymentRequest(8, PaymentMethod.VNPAY);
        User admin = User.builder().id(99).role(Role.ADMIN).name("Admin").email("admin@example.com").password("encoded").build();
        when(orderRepository.findById(8)).thenReturn(Optional.of(order));

        assertThrows(AccessDeniedException.class,
                () -> paymentService.createPayment(admin.getId(), request, "127.0.0.1"));

        verify(paymentRepository, never()).save(any());
        verify(vnPayService, never()).createPaymentUrl(any(), any(), any());
        verify(momoService, never()).createPaymentUrl(any(), any(), any());
    }

    @Test
    void orderWithoutOwnerCannotCreatePaymentOrInvokeGateway() {
        Order order = pendingOrder(null, 125.0);
        CreatePaymentRequest request = paymentRequest(8, PaymentMethod.MOMO);
        when(orderRepository.findById(8)).thenReturn(Optional.of(order));

        assertThrows(AccessDeniedException.class,
                () -> paymentService.createPayment(42, request, "127.0.0.1"));

        verify(paymentRepository, never()).save(any());
        verify(vnPayService, never()).createPaymentUrl(any(), any(), any());
        verify(momoService, never()).createPaymentUrl(any(), any(), any());
    }

    @Test
    void orderWithIdLessOwnerCannotCreatePaymentOrInvokeGateway() {
        Order order = pendingOrder(user(null), 125.0);
        CreatePaymentRequest request = paymentRequest(8, PaymentMethod.MOMO);
        when(orderRepository.findById(8)).thenReturn(Optional.of(order));

        assertThrows(AccessDeniedException.class,
                () -> paymentService.createPayment(42, request, "127.0.0.1"));

        verify(paymentRepository, never()).save(any());
        verify(vnPayService, never()).createPaymentUrl(any(), any(), any());
        verify(momoService, never()).createPaymentUrl(any(), any(), any());
    }

    @Test
    void missingOrderPreservesNotFoundBehavior() {
        CreatePaymentRequest request = paymentRequest(404, PaymentMethod.VNPAY);
        when(orderRepository.findById(404)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> paymentService.createPayment(42, request, "127.0.0.1"));

        verify(paymentRepository, never()).save(any());
        verify(vnPayService, never()).createPaymentUrl(any(), any(), any());
        verify(momoService, never()).createPaymentUrl(any(), any(), any());
    }

    @Test
    void ownerCannotCreatePaymentForNonPendingOrder() {
        Order order = Order.builder()
                .id(8)
                .user(user(42))
                .status(OrderStatus.PROCESSING)
                .totalAmount(125.0)
                .build();
        CreatePaymentRequest request = paymentRequest(8, PaymentMethod.VNPAY);
        when(orderRepository.findById(8)).thenReturn(Optional.of(order));

        assertThrows(IllegalStateException.class,
                () -> paymentService.createPayment(42, request, "127.0.0.1"));

        verify(paymentRepository, never()).save(any());
        verify(vnPayService, never()).createPaymentUrl(any(), any(), any());
        verify(momoService, never()).createPaymentUrl(any(), any(), any());
    }

    @Test
    void paymentMethodPersistsAsString() throws Exception {
        Enumerated annotation = Payment.class.getDeclaredField("method").getAnnotation(Enumerated.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(EnumType.STRING);
    }

    @Test
    void failedDuplicateCallbackCannotRevertSuccessfulPayment() {
        Order order = Order.builder().id(8).status(OrderStatus.PROCESSING).totalAmount(125.0).build();
        LocalDateTime paidAt = LocalDateTime.of(2026, 10, 6, 12, 0);
        Payment payment = Payment.builder()
                .id(9)
                .order(order)
                .amount(125.0)
                .method(PaymentMethod.MOMO)
                .status(PaymentStatus.SUCCESS)
                .paidAt(paidAt)
                .build();
        Map<String, String> callback = Map.of("orderId", "8_12345", "resultCode", "99");
        when(momoService.verifyReturnUrl(callback)).thenReturn(true);
        when(orderRepository.findById(8)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(8)).thenReturn(Optional.of(payment));

        paymentService.handleMomoReturn(callback);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getPaidAt()).isEqualTo(paidAt);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        verify(paymentRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
    }

    private Order pendingOrder(User owner, double totalAmount) {
        return Order.builder()
                .id(8)
                .user(owner)
                .status(OrderStatus.PENDING)
                .totalAmount(totalAmount)
                .build();
    }

    private User user(Integer id) {
        return User.builder()
                .id(id)
                .name("User")
                .email("user@example.com")
                .password("encoded")
                .build();
    }

    private CreatePaymentRequest paymentRequest(int orderId, PaymentMethod method) {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setOrderId(orderId);
        request.setMethod(method);
        return request;
    }
}
