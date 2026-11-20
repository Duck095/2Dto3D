package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.request.AddCartItemRequest;
import com.exe.morphia3d.dto.request.UpdateCartItemRequest;
import com.exe.morphia3d.dto.response.CartItemResponse;
import com.exe.morphia3d.dto.response.CartResponse;
import com.exe.morphia3d.entity.Cart;
import com.exe.morphia3d.entity.CartItem;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.PriceEstimation;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.PrinterStatus;
import com.exe.morphia3d.repository.CartItemRepository;
import com.exe.morphia3d.repository.CartRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.PriceEstimationRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.service.CartApiException;
import com.exe.morphia3d.service.CartService;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class CartServiceImpl implements CartService {
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final Model3dRepository modelRepository;
    private final PriceEstimationRepository estimateRepository;
    private final int maxQuantity;
    private final FeatureGate featureGate;

    public CartServiceImpl(CartRepository cartRepository,
                           CartItemRepository cartItemRepository,
                           UserRepository userRepository,
                           Model3dRepository modelRepository,
                           PriceEstimationRepository estimateRepository,
                           FeatureGate featureGate,
                           @Value("${commerce.cart.max-quantity:99}") int maxQuantity) {
        if (maxQuantity < 1) {
            throw new IllegalArgumentException("commerce.cart.max-quantity must be at least 1");
        }
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.userRepository = userRepository;
        this.modelRepository = modelRepository;
        this.estimateRepository = estimateRepository;
        this.maxQuantity = maxQuantity;
        this.featureGate = featureGate;
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponse getCart(Integer authenticatedUserId) {
        featureGate.requireEnabled(FeatureFamily.CART);
        requireAuthenticatedUser(authenticatedUserId);
        Cart cart = cartRepository.findByUserId(authenticatedUserId).orElse(null);
        if (cart == null) {
            return new CartResponse(List.of());
        }
        List<CartItemResponse> items = cartItemRepository.findAllByCartIdOrderByIdAsc(cart.getId()).stream()
                .map(this::toResponse)
                .toList();
        return new CartResponse(items);
    }

    @Override
    @Transactional
    public CartItemResponse addItem(Integer authenticatedUserId, AddCartItemRequest request) {
        featureGate.requireEnabled(FeatureFamily.CART);
        requireAuthenticatedUser(authenticatedUserId);
        if (request == null || request.modelId() == null || request.estimateId() == null
                || request.quantity() == null || request.quantity() < 1) {
            throw badRequest("CART_REQUEST_INVALID", "Model, estimate, and positive quantity are required");
        }
        if (request.quantity() > maxQuantity) {
            throw quantityLimitExceeded(HttpStatus.BAD_REQUEST);
        }

        Model3d model = modelRepository.findById(request.modelId())
                .orElseThrow(() -> notFound("CART_MODEL_NOT_FOUND", "Model was not found"));
        validateModelAvailability(model, authenticatedUserId);

        PriceEstimation estimate = estimateRepository.findById(request.estimateId())
                .orElseThrow(() -> notFound("CART_ESTIMATE_NOT_FOUND", "Price estimate was not found"));
        if (estimate.getModel() == null || !Objects.equals(estimate.getModel().getId(), model.getId())) {
            throw badRequest("CART_ESTIMATE_MODEL_MISMATCH", "Estimate does not belong to the requested model");
        }
        validateEstimateConfiguration(estimate);

        Cart cart = findOrCreateLockedCart(authenticatedUserId);
        CartItem existing = cartItemRepository.findBySelectionForUpdate(
                cart.getId(), model.getId(), estimate.getId()).orElse(null);
        if (existing != null) {
            if (request.quantity() > maxQuantity - existing.getQuantity()) {
                throw quantityLimitExceeded(HttpStatus.CONFLICT);
            }
            existing.setQuantity(existing.getQuantity() + request.quantity());
            return toResponse(existing);
        }

        CartItem item = new CartItem();
        item.setCart(cart);
        item.setModel(model);
        item.setEstimate(estimate);
        item.setQuantity(request.quantity());
        return toResponse(cartItemRepository.saveAndFlush(item));
    }

    @Override
    @Transactional
    public CartItemResponse updateItem(Integer authenticatedUserId, Integer itemId,
                                       UpdateCartItemRequest request) {
        featureGate.requireEnabled(FeatureFamily.CART);
        requireAuthenticatedUser(authenticatedUserId);
        if (request == null || request.quantity() == null || request.quantity() < 1) {
            throw badRequest("CART_REQUEST_INVALID", "Quantity must be greater than zero");
        }
        if (request.quantity() > maxQuantity) {
            throw quantityLimitExceeded(HttpStatus.BAD_REQUEST);
        }
        CartItem item = findOwnedItemForUpdate(authenticatedUserId, itemId);
        item.setQuantity(request.quantity());
        return toResponse(item);
    }

    @Override
    @Transactional
    public void deleteItem(Integer authenticatedUserId, Integer itemId) {
        featureGate.requireEnabled(FeatureFamily.CART);
        requireAuthenticatedUser(authenticatedUserId);
        CartItem item = findOwnedItemForUpdate(authenticatedUserId, itemId);
        cartItemRepository.delete(item);
    }

    private Cart findOrCreateLockedCart(Integer userId) {
        Cart existing = cartRepository.findByUserIdForUpdate(userId).orElse(null);
        if (existing != null) {
            return existing;
        }

        User owner = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> notFound("CART_OWNER_NOT_FOUND", "Authenticated user was not found"));
        existing = cartRepository.findByUserIdForUpdate(userId).orElse(null);
        if (existing != null) {
            return existing;
        }

        Cart cart = new Cart();
        cart.setUser(owner);
        return cartRepository.saveAndFlush(cart);
    }

    private CartItem findOwnedItemForUpdate(Integer userId, Integer itemId) {
        if (itemId == null || itemId < 1) {
            throw notFound("CART_ITEM_NOT_FOUND", "Cart item was not found");
        }
        CartItem observed = cartItemRepository.findById(itemId)
                .orElseThrow(() -> notFound("CART_ITEM_NOT_FOUND", "Cart item was not found"));
        if (observed.getCart() == null || observed.getCart().getId() == null) {
            throw notFound("CART_ITEM_NOT_FOUND", "Cart item was not found");
        }

        Cart cart = cartRepository.findByIdForUpdate(observed.getCart().getId())
                .orElseThrow(() -> notFound("CART_ITEM_NOT_FOUND", "Cart item was not found"));
        if (cart.getUser() == null || !Objects.equals(cart.getUser().getId(), userId)) {
            throw notFound("CART_ITEM_NOT_FOUND", "Cart item was not found");
        }
        CartItem item = cartItemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> notFound("CART_ITEM_NOT_FOUND", "Cart item was not found"));
        if (item.getCart() == null || !Objects.equals(item.getCart().getId(), cart.getId())) {
            throw notFound("CART_ITEM_NOT_FOUND", "Cart item was not found");
        }
        return item;
    }

    private void validateModelAvailability(Model3d model, Integer userId) {
        ModelType sourceType = model.getSourceType();
        if (sourceType == ModelType.AI_MODEL || sourceType == ModelType.CUSTOMER_CUSTOMIZED) {
            // Check ownership before status so callers cannot distinguish private model states.
            if (model.getUser() == null || !Objects.equals(model.getUser().getId(), userId)) {
                throw notFound("CART_MODEL_NOT_FOUND", "Model was not found");
            }
            if (model.getStatus() == null || model.getStatus() == ModelStatus.INACTIVE) {
                throw conflict("CART_MODEL_UNAVAILABLE", "Model is not available for cart selection");
            }
            return;
        }
        if (sourceType != ModelType.DESIGNER_MODEL) {
            throw notFound("CART_MODEL_NOT_FOUND", "Model was not found");
        }
        if (model.getStatus() != ModelStatus.ACTIVE) {
            throw conflict("CART_MODEL_UNAVAILABLE", "Model is not available for cart selection");
        }
    }

    private void validateEstimateConfiguration(PriceEstimation estimate) {
        boolean amountValid = false;
        if (estimate.getTotalPriceVnd() != null) {
            amountValid = estimate.getTotalPriceVnd() > 0;
        } else if (estimate.getTotalPrice() != null && Double.isFinite(estimate.getTotalPrice())
                && estimate.getTotalPrice() > 0) {
            try { java.math.BigDecimal.valueOf(estimate.getTotalPrice()).longValueExact(); amountValid = true; }
            catch (ArithmeticException ignored) { amountValid = false; }
        }
        if (estimate.getMaterial() == null || estimate.getPrinter() == null
                || estimate.getMaterial().getIsAvailable() != MaterialStatus.AVAILABLE
                || estimate.getPrinter().getStatus() != PrinterStatus.ACTIVE
                || !amountValid) {
            throw conflict("CART_CONFIGURATION_UNAVAILABLE",
                    "Estimate material, printer, or stored amount is unavailable");
        }
    }

    private CartItemResponse toResponse(CartItem item) {
        PriceEstimation estimate = item.getEstimate();
        Long price = estimate.getTotalPriceVnd();
        if (price == null && estimate.getTotalPrice() != null && Double.isFinite(estimate.getTotalPrice())) {
            try { price = java.math.BigDecimal.valueOf(estimate.getTotalPrice()).longValueExact(); }
            catch (ArithmeticException ignored) { price = null; }
        }
        return new CartItemResponse(item.getId(), item.getModel().getId(), estimate.getId(), item.getQuantity(),
                estimate.getMaterial() == null ? null : estimate.getMaterial().getId(),
                estimate.getPrinter() == null ? null : estimate.getPrinter().getId(), price,
                estimate.getPolicyVersion(), estimate.getEstimatedAt() == null ? null : estimate.getEstimatedAt().plusMinutes(15));
    }

    private void requireAuthenticatedUser(Integer userId) {
        if (userId == null || userId < 1) {
            throw new CartApiException(HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", "Authenticated user is required");
        }
    }

    private CartApiException badRequest(String code, String message) {
        return new CartApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private CartApiException notFound(String code, String message) {
        return new CartApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private CartApiException conflict(String code, String message) {
        return new CartApiException(HttpStatus.CONFLICT, code, message);
    }

    private CartApiException quantityLimitExceeded(HttpStatus status) {
        return new CartApiException(status, "CART_QUANTITY_LIMIT_EXCEEDED",
                "Cart item quantity cannot exceed " + maxQuantity);
    }
}
