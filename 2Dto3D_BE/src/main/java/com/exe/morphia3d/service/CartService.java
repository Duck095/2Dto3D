package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.response.CartResponse;
import com.exe.morphia3d.dto.request.AddCartItemRequest;
import com.exe.morphia3d.dto.request.UpdateCartItemRequest;
import com.exe.morphia3d.dto.response.CartItemResponse;

public interface CartService {
    CartResponse getCart(Integer authenticatedUserId);

    CartItemResponse addItem(Integer authenticatedUserId, AddCartItemRequest request);

    CartItemResponse updateItem(Integer authenticatedUserId, Integer itemId, UpdateCartItemRequest request);

    void deleteItem(Integer authenticatedUserId, Integer itemId);

}
