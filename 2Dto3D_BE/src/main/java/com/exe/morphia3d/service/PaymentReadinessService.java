package com.exe.morphia3d.service;

import com.exe.morphia3d.config.PricingPolicyProperties;
import com.exe.morphia3d.domain.payment.PaymentAttemptTransitionPolicy;
import com.exe.morphia3d.domain.payment.PaymentReadinessPolicy;
import com.exe.morphia3d.dto.response.PaymentReadinessResponse;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.OrderItem;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.repository.OrderItemRepository;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.PaymentReconciliationCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PaymentReadinessService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentReconciliationCaseRepository reconciliationRepository;
    private final PricingPolicyProperties pricingPolicyProperties;
    private final PaymentReadinessPolicy readinessPolicy;
    private final PaymentAttemptTransitionPolicy transitionPolicy;
    private final Map<PaymentProvider, PaymentAttemptGateway> gateways;

    public PaymentReadinessService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PaymentAttemptRepository attemptRepository,
            PaymentReconciliationCaseRepository reconciliationRepository,
            PricingPolicyProperties pricingPolicyProperties,
            PaymentReadinessPolicy readinessPolicy,
            PaymentAttemptTransitionPolicy transitionPolicy,
            List<PaymentAttemptGateway> gateways) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.attemptRepository = attemptRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.pricingPolicyProperties = pricingPolicyProperties;
        this.readinessPolicy = readinessPolicy;
        this.transitionPolicy = transitionPolicy;
        EnumMap<PaymentProvider, PaymentAttemptGateway> providers = new EnumMap<>(PaymentProvider.class);
        gateways.forEach(gateway -> providers.put(gateway.provider(), gateway));
        this.gateways = Map.copyOf(providers);
    }

    @Transactional(readOnly = true)
    public PaymentReadinessResponse getReadiness(Integer ownerId, Integer orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> notFound("Order was not found"));
        requireOwner(order, ownerId);
        return evaluate(order, true);
    }

    /** Re-evaluates under the caller's Order lock before a new attempt is inserted. */
    public PaymentReadinessResponse evaluate(Order order, boolean includeAttemptLocks) {
        String blocker = baseBlocker(order);
        if (blocker == null && includeAttemptLocks) {
            if (reconciliationRepository.existsByOrderIdAndResolutionState(order.getId(), "OPEN")) {
                blocker = "RECONCILIATION_REQUIRED";
            } else if (attemptRepository.findByOrderIdOrderByCreatedAtDescAttemptIdDesc(order.getId()).stream()
                    .anyMatch(attempt -> transitionPolicy.blocksNewAttempt(attempt.getStatus()))) {
                blocker = "PAYMENT_ATTEMPT_UNRESOLVED";
            }
        }
        return new PaymentReadinessResponse(order.getId(), blocker == null, blocker,
                order.getTotalAmountVnd(), order.getShippingAmountVnd(), order.getPayableAmountVnd(),
                providerAvailability(), isShippingConfirmed(order), isPayableTotalFrozen(order));
    }

    public boolean providerAvailable(PaymentProvider provider) {
        PaymentAttemptGateway gateway = gateways.get(provider);
        return gateway != null && gateway.isAvailable();
    }

    public PaymentAttemptGateway gateway(PaymentProvider provider) {
        return gateways.get(provider);
    }

    public boolean retryAllowed(Order order, PaymentProvider provider) {
        PaymentReadinessResponse readiness = evaluate(order, true);
        return readiness.eligible() && Boolean.TRUE.equals(readiness.providerAvailability().get(provider));
    }

    private String baseBlocker(Order order) {
        if (order.getStatus() != OrderStatus.PENDING) return "ORDER_NOT_PAYABLE";
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        if (items.isEmpty()) return "ORDER_ITEMS_UNAVAILABLE";

        Set<String> approved = pricingPolicyProperties.getApprovedCommercialPolicyVersions();
        boolean commercialPricingApproved = !approved.isEmpty() && items.stream().allMatch(item ->
                item.getPriceSnapshotPolicyVersion() != null
                        && approved.contains(item.getPriceSnapshotPolicyVersion()));

        Long subtotal = order.getTotalAmountVnd();
        boolean itemTotalMatches = false;
        if (subtotal != null && subtotal > 0) {
            try {
                long calculated = 0;
                for (OrderItem item : items) {
                    if (item.getPriceSnapshotVnd() == null || item.getPriceSnapshotVnd() <= 0
                            || item.getQuantity() == null || item.getQuantity() <= 0) {
                        calculated = -1;
                        break;
                    }
                    calculated = Math.addExact(calculated,
                            Math.multiplyExact(item.getPriceSnapshotVnd(), item.getQuantity().longValue()));
                }
                itemTotalMatches = calculated == subtotal;
            } catch (ArithmeticException ignored) {
                itemTotalMatches = false;
            }
        }

        if (!itemTotalMatches) return "PRODUCT_SUBTOTAL_MISMATCH";
        if (!commercialPricingApproved) return "COMMERCIAL_PRICING_UNAPPROVED";

        boolean shippingConfirmed = isShippingConfirmed(order);
        Instant frozenAt = order.getPaymentTotalFrozenAt();
        PaymentReadinessPolicy.Snapshot snapshot = new PaymentReadinessPolicy.Snapshot(
                subtotal, order.getShippingAmountVnd() == null ? -1 : order.getShippingAmountVnd(),
                order.getPayableAmountVnd() == null ? -1 : order.getPayableAmountVnd(), frozenAt,
                shippingConfirmed, commercialPricingApproved);
        return switch (readinessPolicy.evaluate(snapshot)) {
            case NONE -> null;
            case COMMERCIAL_PRICING_UNAPPROVED -> "COMMERCIAL_PRICING_UNAPPROVED";
            case SHIPPING_UNCONFIRMED -> "SHIPPING_UNCONFIRMED";
            case TOTAL_NOT_FROZEN -> "PAYABLE_TOTAL_NOT_FROZEN";
            case INVALID_AMOUNT -> "PAYABLE_TOTAL_INVALID";
            case FROZEN_TOTAL_MISMATCH -> "PAYABLE_TOTAL_MISMATCH";
        };
    }

    private Map<PaymentProvider, Boolean> providerAvailability() {
        EnumMap<PaymentProvider, Boolean> availability = new EnumMap<>(PaymentProvider.class);
        availability.put(PaymentProvider.PAYOS, providerAvailable(PaymentProvider.PAYOS));
        return availability;
    }

    private boolean isShippingConfirmed(Order order) {
        return order.getShippingAmountVnd() != null && order.getShippingAmountVnd() >= 0
                && order.getShippingConfirmationReference() != null
                && !order.getShippingConfirmationReference().isBlank();
    }

    private boolean isPayableTotalFrozen(Order order) {
        return order.getPaymentTotalFrozenAt() != null && order.getPayableAmountVnd() != null;
    }

    private void requireOwner(Order order, Integer ownerId) {
        if (ownerId == null || order.getUser() == null || !Objects.equals(order.getUser().getId(), ownerId)) {
            throw notFound("Order was not found");
        }
    }

    private PaymentApiException notFound(String message) {
        return new PaymentApiException(404, "PAYMENT_ORDER_NOT_FOUND", message);
    }
}
