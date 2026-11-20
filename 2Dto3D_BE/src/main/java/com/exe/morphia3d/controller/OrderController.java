package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.UpdateOrderStatusRequest;
import com.exe.morphia3d.dto.response.OrderResponse;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(Authentication authentication) {
        Integer userId = currentUserId(authentication);
        OrderResponse response = orderService.createOrderFromCart(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/from-cart-item/{cartItemId}")
    public ResponseEntity<OrderResponse> createOrderFromCartItem(
            Authentication authentication,
            @PathVariable Integer cartItemId) {
        Integer userId = currentUserId(authentication);
        OrderResponse response = orderService.createOrderFromCartItem(userId, cartItemId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable Integer id,
            @RequestBody UpdateOrderStatusRequest request) {
        return ResponseEntity.ok(orderService.updateOrderStatus(id, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(Authentication authentication, @PathVariable Integer id) {
        return ResponseEntity.ok(orderService.getOrderById(currentUserId(authentication), id));
    }

    private Integer currentUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new org.springframework.security.access.AccessDeniedException("Authenticated user principal is required");
        }
        return principal.getId();
    }
}
