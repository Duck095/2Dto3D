package com.exe.morphia3d.service;

import com.exe.morphia3d.config.PricingPolicyProperties;
import com.exe.morphia3d.domain.payment.PaymentAttemptTransitionPolicy;
import com.exe.morphia3d.domain.payment.PaymentReadinessPolicy;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.OrderItem;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.repository.OrderItemRepository;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.PaymentReconciliationCaseRepository;
import com.exe.morphia3d.service.payment.UnavailablePaymentAttemptGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentReadinessServiceTest {
    @Test
    void blocksUntilFounderPricingApprovalShippingConfirmationAndFrozenTotalArePresent() {
        Order order = Order.builder().id(7).user(User.builder().id(42).build()).status(OrderStatus.PENDING)
                .totalAmountVnd(125000L).build();
        OrderItem item = OrderItem.builder().quantity(1).priceSnapshotVnd(125000L)
                .priceSnapshotPolicyVersion("commercial-founder-v1").build();
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentReconciliationCaseRepository cases = mock(PaymentReconciliationCaseRepository.class);
        when(orders.findById(7)).thenReturn(Optional.of(order));
        when(items.findAllByOrderId(7)).thenReturn(List.of(item));
        when(attempts.findByOrderIdOrderByCreatedAtDescAttemptIdDesc(7)).thenReturn(List.of());
        when(cases.existsByOrderIdAndResolutionState(7, "OPEN")).thenReturn(false);
        PricingPolicyProperties pricing = new PricingPolicyProperties(); // Empty allowlist is fail-closed.
        PaymentReadinessService service = new PaymentReadinessService(orders, items, attempts, cases, pricing,
                new PaymentReadinessPolicy(), new PaymentAttemptTransitionPolicy(), List.of(
                new UnavailablePaymentAttemptGateway(PaymentProvider.PAYOS)));

        assertThat(service.getReadiness(42, 7).blocker()).isEqualTo("COMMERCIAL_PRICING_UNAPPROVED");

        pricing.setApprovedCommercialPolicyVersions(java.util.Set.of("commercial-founder-v1"));
        assertThat(service.getReadiness(42, 7).blocker()).isEqualTo("SHIPPING_UNCONFIRMED");

        order.setShippingAmountVnd(10000L);
        order.setShippingConfirmationReference("shipping-confirmation-1");
        order.setPayableAmountVnd(135000L);
        order.setPaymentTotalFrozenAt(Instant.parse("2026-11-12T10:00:00Z"));
        var ready = service.getReadiness(42, 7);
        assertThat(ready.eligible()).isTrue();
        assertThat(ready.productSubtotalVnd()).isEqualTo(125000L);
        assertThat(ready.shippingAmountVnd()).isEqualTo(10000L);
        assertThat(ready.payableAmountVnd()).isEqualTo(135000L);
        assertThat(ready.providerAvailability()).containsEntry(PaymentProvider.PAYOS, false)
                .doesNotContainKeys(PaymentProvider.VNPAY, PaymentProvider.MOMO);
    }
}
