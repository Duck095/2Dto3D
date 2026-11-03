package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.request.UpdateOrderStatusRequest;
import com.exe.morphia3d.dto.response.OrderItemResponse;
import com.exe.morphia3d.dto.response.OrderResponse;
import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.mapper.OrderItemMapper;
import com.exe.morphia3d.mapper.OrderMapper;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.service.OrderService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;


    @Override
    @Transactional
    public OrderResponse createOrderFromCart(Integer userId) {
        // 1. Lấy cart của user
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("User chưa có giỏ hàng"));

        List<CartItem> cartItems = cartItemRepository.findAllByCartId(cart.getId());
        if (cartItems.isEmpty()) {
            throw new IllegalStateException("Giỏ hàng đang trống, không thể tạo đơn hàng");
        }

        if (cart.getUser() == null || !Objects.equals(cart.getUser().getId(), userId)) {
            throw new AccessDeniedException("Không thể thanh toán giỏ hàng của người dùng khác");
        }

        Printer printer = requireSinglePrinter(cartItems);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User không tồn tại: " + userId));

        // 3. Tính tổng tiền từ price_snapshot lúc thêm vào cart
        double totalAmount = cartItems.stream()
                .mapToDouble(ci -> ci.getEstimate().getTotalPrice() * ci.getQuantity())
                .sum();

        // 4. Tạo Order
        Order order = Order.builder()
                .user(user)
                .printer(printer)
                .totalAmount(totalAmount)
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
                    .priceSnapshot(ci.getEstimate().getTotalPrice()).build();
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
        // 1. Lấy cart item, đồng thời verify nó thuộc đúng user (tránh user A xóa order của user B)
        CartItem cartItem = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new EntityNotFoundException("CartItem không tồn tại: " + cartItemId));

        if (cartItem.getCart() == null
                || cartItem.getCart().getUser() == null
                || !Objects.equals(cartItem.getCart().getUser().getId(), userId)) {
            throw new AccessDeniedException("Không thể thanh toán CartItem của người dùng khác");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User không tồn tại: " + userId));

        Printer printer = requirePrinter(cartItem);

        // 3. Tính tổng tiền (chỉ riêng item này)
        double totalAmount = cartItem.getEstimate().getTotalPrice() * cartItem.getQuantity();

        // 4. Tạo Order
        Order order = Order.builder()
                .user(user)
                .printer(printer)
                .totalAmount(totalAmount)
                .build();
        OrderResponse orderResponse = orderMapper.toResponse(orderRepository.save(order));

        // 5. Tạo OrderItem từ CartItem (snapshot giá)
        OrderItem orderItem = OrderItem.builder()
                .order(order)
                .model(cartItem.getModel())
                .estimate(cartItem.getEstimate())
                .quantity(cartItem.getQuantity())
                .priceSnapshot(cartItem.getEstimate().getTotalPrice())
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
        Order order = orderRepository.findById(orderId)
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

    private boolean samePrinter(Printer first, Printer second) {
        if (first == second) {
            return true;
        }
        return first.getId() != null && Objects.equals(first.getId(), second.getId());
    }

    private void validateStatusTransition(OrderStatus current, OrderStatus next) {
        if (current == OrderStatus.SUCCEEDED || current == OrderStatus.CANCELED) {
            throw new IllegalStateException(
                    "Order đã ở trạng thái cuối (" + current + "), không thể chuyển sang " + next);
        }
    }
}
