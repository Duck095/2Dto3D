package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.UpdateOrderStatusRequest;
import com.exe.morphia3d.dto.response.OrderResponse;

public interface OrderService {
    OrderResponse createOrderFromCart(Integer userId);
    OrderResponse createOrderFromCartItem(Integer userId, Integer cartItemId);
    OrderResponse updateOrderStatus(Integer orderId, UpdateOrderStatusRequest request);
    OrderResponse getOrderById(Integer userId, Integer orderId);
}
