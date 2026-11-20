package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.AddCartItemRequest;
import com.exe.morphia3d.dto.request.UpdateCartItemRequest;
import com.exe.morphia3d.dto.response.CartItemResponse;
import com.exe.morphia3d.dto.response.CartResponse;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;

    @GetMapping
    public CartResponse getCart(Authentication authentication) {
        return cartService.getCart(currentUserId(authentication));
    }

    @PostMapping("/items")
    public CartItemResponse addItem(Authentication authentication, @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(currentUserId(authentication), request);
    }

    @PatchMapping("/items/{id}")
    public CartItemResponse updateItem(Authentication authentication, @PathVariable Integer id,
                                       @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateItem(currentUserId(authentication), id, request);
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> deleteItem(Authentication authentication, @PathVariable Integer id) {
        cartService.deleteItem(currentUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private Integer currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)
                || principal.getId() == null || principal.getId() < 1) {
            throw new AccessDeniedException("Authenticated user principal is required");
        }
        return principal.getId();
    }
}
