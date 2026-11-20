package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.request.UpdateOrderStatusRequest;
import com.exe.morphia3d.dto.response.OrderItemResponse;
import com.exe.morphia3d.dto.response.OrderResponse;
import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.enums.PrinterStatus;
import com.exe.morphia3d.mapper.OrderItemMapper;
import com.exe.morphia3d.mapper.OrderMapper;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.service.OrderEstimateExpiredException;
import com.exe.morphia3d.service.OrderConfigurationUnavailableException;
import com.exe.morphia3d.service.OrderCartEmptyException;
import com.exe.morphia3d.service.OrderStatusRequestException;
import com.exe.morphia3d.service.OrderStatusTransitionException;
import com.exe.morphia3d.service.OrderService;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private static final Duration ESTIMATE_MAX_AGE = Duration.ofMinutes(15);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final FeatureGate featureGate;


    @Override
    @Transactional
    public OrderResponse createOrderFromCart(Integer userId) {
        featureGate.requireEnabled(FeatureFamily.ORDER);
        featureGate.requireEnabled(FeatureFamily.CHECKOUT);
        // Serialize checkout attempts for this cart so only one request can consume its items.
        Cart cart = cartRepository.findByUserIdForUpdate(userId)
                .orElseThrow(OrderCartEmptyException::new);

        if (cart.getUser() == null || !Objects.equals(cart.getUser().getId(), userId)) {
            throw new AccessDeniedException("Không thể thanh toán giỏ hàng của người dùng khác");
        }

        List<CartItem> cartItems = cartItemRepository.findAllByCartIdForUpdate(cart.getId());
        if (cartItems.isEmpty()) {
            throw new OrderCartEmptyException();
        }

        LocalDateTime checkoutReferenceTime = LocalDateTime.now();
        cartItems.forEach(item -> validateCartItemForCheckout(item, userId, checkoutReferenceTime));
        Printer printer = requireSinglePrinter(cartItems);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User không tồn tại: " + userId));

        long totalAmountVnd = cartItems.stream().mapToLong(this::lineAmountVnd)
                .reduce(0L, Math::addExact);
        double totalAmount = (double) totalAmountVnd;
        if (totalAmountVnd <= 0 || !Double.isFinite(totalAmount)) throw new IllegalStateException("Cart total is invalid");

        // 4. Tạo Order
        Order order = Order.builder()
                .user(user)
                .printer(printer)
                .totalAmount(totalAmount)
                .totalAmountVnd(totalAmountVnd)
                .build();
        OrderResponse orderResponse = orderMapper.toResponse(orderRepository.save(order));

        // 5. Convert từng CartItem -> OrderItem (snapshot giá tại thời điểm đặt hàng)
        List<OrderItemResponse> orderItemsResponse = new ArrayList<>();
        for (CartItem ci : cartItems) {
            OrderItem oi = OrderItem.builder()
                    .order(order)
                    .model(ci.getModel())
                    .estimate(ci.getEstimate())
                    .quantity(ci.getQuantity())
                    .priceSnapshot((double) unitAmountVnd(ci.getEstimate()))
                    .priceSnapshotVnd(unitAmountVnd(ci.getEstimate()))
                    .priceSnapshotPolicyVersion(ci.getEstimate().getPolicyVersion())
                    .pricingPolicySnapshot(ci.getEstimate().getPricingPolicySnapshot()).build();
            OrderItemResponse itemResponse = orderItemMapper.toResponse(orderItemRepository.save(oi));
            orderItemsResponse.add(itemResponse);
        }

        // 6. Xóa cart sau khi checkout thành công
        cartItemRepository.deleteAll(cartItems);

        orderResponse.setItems(orderItemsResponse);

        return orderResponse;
    }

    @Override
    @Transactional
    public OrderResponse createOrderFromCartItem(Integer userId, Integer cartItemId) {
        featureGate.requireEnabled(FeatureFamily.ORDER);
        featureGate.requireEnabled(FeatureFamily.CHECKOUT);
        // Lock in cart-then-item order, matching full-cart checkout to avoid duplicate orders and deadlocks.
        CartItem observedItem = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new EntityNotFoundException("CartItem không tồn tại: " + cartItemId));
        if (observedItem.getCart() == null || observedItem.getCart().getId() == null) {
            throw new IllegalStateException("CartItem không thuộc giỏ hàng hợp lệ");
        }
        Cart cart = cartRepository.findByIdForUpdate(observedItem.getCart().getId())
                .orElseThrow(() -> new EntityNotFoundException("Giỏ hàng không tồn tại"));
        CartItem cartItem = cartItemRepository.findByIdForUpdate(cartItemId)
                .orElseThrow(() -> new EntityNotFoundException("CartItem không tồn tại: " + cartItemId));

        if (cartItem.getCart() == null
                || !Objects.equals(cartItem.getCart().getId(), cart.getId())
                || cartItem.getCart().getUser() == null
                || !Objects.equals(cartItem.getCart().getUser().getId(), userId)) {
            throw new AccessDeniedException("Không thể thanh toán CartItem của người dùng khác");
        }

        LocalDateTime checkoutReferenceTime = LocalDateTime.now();
        validateCartItemForCheckout(cartItem, userId, checkoutReferenceTime);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User không tồn tại: " + userId));

        Printer printer = requirePrinter(cartItem);

        // 3. Tính tổng tiền (chỉ riêng item này)
        long totalAmountVnd = lineAmountVnd(cartItem);
        double totalAmount = (double) totalAmountVnd;
        if (totalAmountVnd <= 0 || !Double.isFinite(totalAmount)) throw new IllegalStateException("CartItem total is invalid");

        // 4. Tạo Order
        Order order = Order.builder()
                .user(user)
                .printer(printer)
                .totalAmount(totalAmount)
                .totalAmountVnd(totalAmountVnd)
                .build();
        OrderResponse orderResponse = orderMapper.toResponse(orderRepository.save(order));

        // 5. Tạo OrderItem từ CartItem (snapshot giá)
        OrderItem orderItem = OrderItem.builder()
                .order(order)
                .model(cartItem.getModel())
                .estimate(cartItem.getEstimate())
                .quantity(cartItem.getQuantity())
                .priceSnapshot((double) unitAmountVnd(cartItem.getEstimate()))
                .priceSnapshotVnd(unitAmountVnd(cartItem.getEstimate()))
                .priceSnapshotPolicyVersion(cartItem.getEstimate().getPolicyVersion())
                .pricingPolicySnapshot(cartItem.getEstimate().getPricingPolicySnapshot())
                .build();

        OrderItemResponse orderItemResponse = orderItemMapper.toResponse(orderItemRepository.save(orderItem));

        // 6. Xóa cart item sau khi tạo order thành công
        cartItemRepository.delete(cartItem);

        orderResponse.setItems(List.of(orderItemResponse));

        return orderResponse;
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Integer orderId, UpdateOrderStatusRequest request) {
        featureGate.requireEnabled(FeatureFamily.ORDER);
        if (request == null || request.getStatus() == null) {
            throw new OrderStatusRequestException("Order status is required");
        }

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order không tồn tại: " + orderId));

        validateStatusTransition(order.getStatus(), request.getStatus());

        order.setStatus(request.getStatus());
        if (request.getStatus() == OrderStatus.SUCCEEDED) {
            order.setCompletedDate(LocalDateTime.now());
        }

        order = orderRepository.save(order);
        List<OrderItem> items = orderItemRepository.findAllByOrderId(orderId);
        List<OrderItemResponse> orderItemsResponse = new ArrayList<>();
        for (OrderItem oi : items) {
            OrderItemResponse itemResponse = orderItemMapper.toResponse(oi);
            orderItemsResponse.add(itemResponse);
        }
        OrderResponse orderResponse = orderMapper.toResponse(order);
        orderResponse.setItems(orderItemsResponse);
        return orderResponse;
    }

    @Override
    public OrderResponse getOrderById(Integer userId, Integer orderId) {
        featureGate.requireEnabled(FeatureFamily.ORDER);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order không tồn tại: " + orderId));
        if (order.getUser() == null || !Objects.equals(order.getUser().getId(), userId)) {
            throw new AccessDeniedException("Không thể xem đơn hàng của người dùng khác");
        }
        List<OrderItem> items = orderItemRepository.findAllByOrderId(orderId);
        List<OrderItemResponse> orderItemsResponse = new ArrayList<>();
        for (OrderItem oi : items) {
            OrderItemResponse itemResponse = orderItemMapper.toResponse(oi);
            orderItemsResponse.add(itemResponse);
        }
        OrderResponse orderResponse = orderMapper.toResponse(order);
        orderResponse.setItems(orderItemsResponse);
        return orderResponse;
    }

    private Printer requireSinglePrinter(List<CartItem> cartItems) {
        Printer selected = requirePrinter(cartItems.get(0));
        for (int i = 1; i < cartItems.size(); i++) {
            Printer candidate = requirePrinter(cartItems.get(i));
            if (!samePrinter(selected, candidate)) {
                throw new IllegalStateException("Không thể tạo một đơn hàng từ nhiều máy in khác nhau");
            }
        }
        return selected;
    }

    private Printer requirePrinter(CartItem cartItem) {
        if (cartItem.getEstimate() == null || cartItem.getEstimate().getPrinter() == null) {
            throw new IllegalStateException("CartItem chưa có máy in từ PriceEstimation");
        }
        return cartItem.getEstimate().getPrinter();
    }

    private void validateCartItemForCheckout(CartItem cartItem, Integer userId,
                                             LocalDateTime checkoutReferenceTime) {
        if (cartItem == null || cartItem.getQuantity() == null || cartItem.getQuantity() < 1) {
            throw new IllegalStateException("CartItem quantity must be greater than zero");
        }
        if (cartItem.getModel() == null || cartItem.getEstimate() == null
                || cartItem.getEstimate().getModel() == null
                || cartItem.getModel().getId() == null
                || cartItem.getEstimate().getModel().getId() == null
                || !Objects.equals(cartItem.getModel().getId(), cartItem.getEstimate().getModel().getId())) {
            throw new IllegalStateException("CartItem model does not match its price estimation");
        }
        validateModelAvailability(cartItem.getModel(), userId);
        validateEstimateConfiguration(cartItem.getEstimate());
        validateEstimateFreshness(cartItem.getEstimate(), checkoutReferenceTime);
        lineAmountVnd(cartItem);
    }

    private long lineAmountVnd(CartItem item) {
        try { return Math.multiplyExact(unitAmountVnd(item.getEstimate()), item.getQuantity()); }
        catch (ArithmeticException exception) { throw new IllegalStateException("CartItem total is invalid", exception); }
    }

    private long unitAmountVnd(PriceEstimation estimate) {
        if (estimate == null) throw new IllegalStateException("CartItem price estimation is invalid");
        if (estimate.getTotalPriceVnd() != null) {
            if (estimate.getTotalPriceVnd() <= 0) throw new IllegalStateException("CartItem price estimation is invalid");
            return estimate.getTotalPriceVnd();
        }
        // Compatibility for legacy estimates only when their stored amount is an exact whole VND value.
        Double legacy = estimate.getTotalPrice();
        if (legacy == null || !Double.isFinite(legacy) || legacy <= 0) throw new IllegalStateException("CartItem price estimation is invalid");
        try { return BigDecimal.valueOf(legacy).longValueExact(); }
        catch (ArithmeticException exception) { throw new IllegalStateException("Legacy estimate is not an exact VND amount", exception); }
    }

    private void validateModelAvailability(Model3d model, Integer userId) {
        ModelType sourceType = model.getSourceType();
        if (sourceType == ModelType.AI_MODEL || sourceType == ModelType.CUSTOMER_CUSTOMIZED) {
            if (model.getUser() == null || !Objects.equals(model.getUser().getId(), userId)) {
                throw new AccessDeniedException("Private model is not available to this user");
            }
            if (model.getStatus() == null || model.getStatus() == ModelStatus.INACTIVE) {
                throw new OrderConfigurationUnavailableException();
            }
            return;
        }
        if (sourceType != ModelType.DESIGNER_MODEL || model.getStatus() != ModelStatus.ACTIVE) {
            throw new OrderConfigurationUnavailableException();
        }
    }

    private void validateEstimateConfiguration(PriceEstimation estimate) {
        if (estimate.getMaterial() == null || estimate.getPrinter() == null
                || estimate.getMaterial().getIsAvailable() != MaterialStatus.AVAILABLE
                || estimate.getPrinter().getStatus() != PrinterStatus.ACTIVE) {
            throw new OrderConfigurationUnavailableException();
        }
    }

    static void validateEstimateFreshness(PriceEstimation estimate, LocalDateTime checkoutReferenceTime) {
        LocalDateTime estimatedAt = estimate == null ? null : estimate.getEstimatedAt();
        if (estimatedAt == null || checkoutReferenceTime == null || estimatedAt.isAfter(checkoutReferenceTime)) {
            throw new OrderEstimateExpiredException(
                    "Price estimate is expired or has an invalid timestamp; refresh it before checkout");
        }

        Duration age = Duration.between(estimatedAt, checkoutReferenceTime);
        if (age.compareTo(ESTIMATE_MAX_AGE) >= 0) {
            throw new OrderEstimateExpiredException(
                    "Price estimate is expired or has an invalid timestamp; refresh it before checkout");
        }
    }

    private boolean samePrinter(Printer first, Printer second) {
        if (first == second) {
            return true;
        }
        return first.getId() != null && Objects.equals(first.getId(), second.getId());
    }

    private void validateStatusTransition(OrderStatus current, OrderStatus next) {
        if (next == null) {
            throw new OrderStatusRequestException("Order status is required");
        }
        if (current == null) {
            throw new IllegalStateException("Order has no current status");
        }

        boolean allowed = switch (current) {
            case PENDING -> next == OrderStatus.PROCESSING || next == OrderStatus.CANCELED;
            case PROCESSING -> next == OrderStatus.SUCCEEDED;
            case SUCCEEDED, CANCELED -> false;
        };
        if (allowed) {
            return;
        }

        if (current == OrderStatus.PROCESSING && next == OrderStatus.CANCELED) {
            throw new OrderStatusTransitionException(
                    "Cannot cancel a processing order because fulfillment has not been verified as unstarted");
        }
        throw new OrderStatusTransitionException(
                "Order status transition is not allowed: " + current + " -> " + next);
    }
}
