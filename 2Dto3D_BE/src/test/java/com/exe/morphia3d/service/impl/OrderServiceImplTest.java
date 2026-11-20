package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.response.OrderItemResponse;
import com.exe.morphia3d.dto.response.OrderResponse;
import com.exe.morphia3d.dto.request.UpdateOrderStatusRequest;
import com.exe.morphia3d.entity.Cart;
import com.exe.morphia3d.entity.CartItem;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.OrderItem;
import com.exe.morphia3d.entity.PriceEstimation;
import com.exe.morphia3d.entity.Printer;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.PrinterStatus;
import com.exe.morphia3d.mapper.OrderItemMapper;
import com.exe.morphia3d.mapper.OrderMapper;
import com.exe.morphia3d.repository.CartItemRepository;
import com.exe.morphia3d.repository.CartRepository;
import com.exe.morphia3d.repository.OrderItemRepository;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PrinterRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.service.OrderEstimateExpiredException;
import com.exe.morphia3d.service.OrderConfigurationUnavailableException;
import com.exe.morphia3d.service.OrderCartEmptyException;
import com.exe.morphia3d.feature.FeatureGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private PrinterRepository printerRepository;
    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private FeatureGate featureGate;
    @InjectMocks private OrderServiceImpl orderService;

    @Test
    void fullCartCheckoutWithoutPersistedCartUsesEmptyCartBusinessException() {
        User owner = user(1);
        when(cartRepository.findByUserIdForUpdate(owner.getId())).thenReturn(Optional.empty());

        assertThrows(OrderCartEmptyException.class, () -> orderService.createOrderFromCart(owner.getId()));

        verifyNoInteractions(orderRepository, orderItemRepository, cartItemRepository);
    }

    @Test
    void cartItemCheckoutRejectsAnotherUsersItem() {
        User owner = user(1);
        User attacker = user(2);
        CartItem foreignItem = cartItem(owner, printer(10));
        when(cartRepository.findByIdForUpdate(5)).thenReturn(Optional.of(foreignItem.getCart()));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(foreignItem));
        when(cartItemRepository.findByIdForUpdate(99)).thenReturn(Optional.of(foreignItem));

        assertThrows(AccessDeniedException.class,
                () -> orderService.createOrderFromCartItem(attacker.getId(), 99));
    }

    @Test
    void checkoutRejectsPrivateModelAfterOwnershipChangesSinceCartAdd() {
        User cartOwner = user(1);
        User currentModelOwner = user(2);
        CartItem item = cartItem(cartOwner, printer(10));
        item.setModel(Model3d.builder()
                .id(3)
                .sourceType(ModelType.AI_MODEL)
                .status(ModelStatus.ACTIVE)
                .user(currentModelOwner)
                .build());
        item.getEstimate().setModel(item.getModel());
        when(cartRepository.findByIdForUpdate(5)).thenReturn(Optional.of(item.getCart()));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByIdForUpdate(99)).thenReturn(Optional.of(item));

        assertThrows(AccessDeniedException.class,
                () -> orderService.createOrderFromCartItem(cartOwner.getId(), 99));

        verifyNoInteractions(orderRepository, orderItemRepository);
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void fullCartCheckoutRejectsPrivateModelOwnedByAnotherUser() {
        User cartOwner = user(1);
        Cart cart = new Cart();
        cart.setId(5);
        cart.setUser(cartOwner);
        CartItem item = cartItem(cartOwner, printer(10));
        item.setCart(cart);
        item.setModel(Model3d.builder().id(3).sourceType(ModelType.CUSTOMER_CUSTOMIZED)
                .status(ModelStatus.ACTIVE).user(user(2)).build());
        item.getEstimate().setModel(item.getModel());
        when(cartRepository.findByUserIdForUpdate(cartOwner.getId())).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCartIdForUpdate(cart.getId())).thenReturn(List.of(item));

        assertThrows(AccessDeniedException.class,
                () -> orderService.createOrderFromCart(cartOwner.getId()));

        verifyNoInteractions(orderRepository, orderItemRepository);
        verify(cartItemRepository, never()).deleteAll(any());
    }

    @Test
    void checkoutRejectsModelThatBecameUnavailableAfterEstimateWasCreated() {
        User owner = user(1);
        CartItem item = cartItem(owner, printer(10));
        item.getModel().setStatus(ModelStatus.PENDING);
        when(cartRepository.findByIdForUpdate(5)).thenReturn(Optional.of(item.getCart()));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByIdForUpdate(99)).thenReturn(Optional.of(item));

        assertThrows(OrderConfigurationUnavailableException.class,
                () -> orderService.createOrderFromCartItem(owner.getId(), 99));

        verifyNoInteractions(orderRepository, orderItemRepository);
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void checkoutRejectsMaterialThatBecameUnavailableAfterEstimateWasCreated() {
        User owner = user(1);
        CartItem item = cartItem(owner, printer(10));
        item.getEstimate().getMaterial().setIsAvailable(MaterialStatus.UNAVAILABLE);
        when(cartRepository.findByIdForUpdate(5)).thenReturn(Optional.of(item.getCart()));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByIdForUpdate(99)).thenReturn(Optional.of(item));

        assertThrows(OrderConfigurationUnavailableException.class,
                () -> orderService.createOrderFromCartItem(owner.getId(), 99));

        verifyNoInteractions(orderRepository, orderItemRepository);
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void cartItemCheckoutCopiesPrinterFromPriceEstimation() {
        User owner = user(1);
        Printer expectedPrinter = printer(10);
        CartItem item = cartItem(owner, expectedPrinter);
        when(cartRepository.findByIdForUpdate(5)).thenReturn(Optional.of(item.getCart()));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByIdForUpdate(99)).thenReturn(Optional.of(item));
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderMapper.toResponse(any())).thenReturn(OrderResponse.builder().build());
        when(orderItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderItemMapper.toResponse(any())).thenReturn(OrderItemResponse.builder().build());

        orderService.createOrderFromCartItem(owner.getId(), 99);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        org.mockito.Mockito.verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getPrinter()).isSameAs(expectedPrinter);
    }

    @Test
    void fullCartCheckoutRejectsMixedPrinters() {
        User owner = user(1);
        Cart cart = new Cart();
        cart.setId(5);
        cart.setUser(owner);
        CartItem first = cartItem(owner, printer(10));
        first.setCart(cart);
        CartItem second = cartItem(owner, printer(11));
        second.setCart(cart);
        when(cartRepository.findByUserIdForUpdate(owner.getId())).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCartIdForUpdate(cart.getId())).thenReturn(List.of(first, second));
        assertThrows(IllegalStateException.class,
                () -> orderService.createOrderFromCart(owner.getId()));
    }

    @Test
    void fullCartCheckoutCopiesSharedPrinterFromPriceEstimations() {
        User owner = user(1);
        Printer printer = printer(10);
        Cart cart = new Cart();
        cart.setId(5);
        cart.setUser(owner);
        CartItem first = cartItem(owner, printer);
        first.setCart(cart);
        CartItem second = cartItem(owner, printer);
        second.setId(100);
        second.setCart(cart);
        when(cartRepository.findByUserIdForUpdate(owner.getId())).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCartIdForUpdate(cart.getId())).thenReturn(List.of(first, second));
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderMapper.toResponse(any())).thenReturn(OrderResponse.builder().build());
        when(orderItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderItemMapper.toResponse(any())).thenReturn(OrderItemResponse.builder().build());

        orderService.createOrderFromCart(owner.getId());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        org.mockito.Mockito.verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getPrinter()).isSameAs(printer);
    }

    @Test
    void checkoutUsesExactVndAndCopiesPolicySnapshotIntoConfirmedOrderItem() {
        User owner = user(1);
        Printer printer = printer(10);
        Cart cart = new Cart();
        cart.setId(5);
        cart.setUser(owner);
        CartItem item = cartItem(owner, printer);
        item.setCart(cart);
        item.getEstimate().setTotalPrice(25.0);
        item.getEstimate().setTotalPriceVnd(25L);
        item.getEstimate().setPolicyVersion("pricing-v1");
        item.getEstimate().setPricingPolicySnapshot("{\"policyVersion\":\"pricing-v1\"}");
        when(cartRepository.findByUserIdForUpdate(owner.getId())).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCartIdForUpdate(cart.getId())).thenReturn(List.of(item));
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderMapper.toResponse(any())).thenReturn(OrderResponse.builder().build());
        when(orderItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderItemMapper.toResponse(any())).thenReturn(OrderItemResponse.builder().build());

        orderService.createOrderFromCart(owner.getId());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        org.mockito.Mockito.verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getTotalAmountVnd()).isEqualTo(50L);
        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        org.mockito.Mockito.verify(orderItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getPriceSnapshotVnd()).isEqualTo(25L);
        assertThat(itemCaptor.getValue().getPriceSnapshotPolicyVersion()).isEqualTo("pricing-v1");
        assertThat(itemCaptor.getValue().getPricingPolicySnapshot()).isEqualTo("{\"policyVersion\":\"pricing-v1\"}");
    }

    @Test
    void checkoutRejectsFractionalLegacyVndInsteadOfRounding() {
        User owner = user(1);
        CartItem item = cartItem(owner, printer(10));
        item.getEstimate().setTotalPrice(25.5);
        item.getEstimate().setTotalPriceVnd(null);
        when(cartRepository.findByIdForUpdate(5)).thenReturn(Optional.of(item.getCart()));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByIdForUpdate(99)).thenReturn(Optional.of(item));

        assertThrows(IllegalStateException.class, () -> orderService.createOrderFromCartItem(owner.getId(), 99));
        org.mockito.Mockito.verifyNoInteractions(orderRepository, orderItemRepository);
    }

    @Test
    void orderReadRejectsAnotherUser() {
        Order order = Order.builder().id(12).user(user(1)).build();
        when(orderRepository.findById(12)).thenReturn(Optional.of(order));

        assertThrows(AccessDeniedException.class,
                () -> orderService.getOrderById(2, 12));
    }

    @Test
    void cartItemCheckoutRejectsNonPositiveQuantityBeforeCreatingOrder() {
        User owner = user(1);
        CartItem item = cartItem(owner, printer(10));
        item.setQuantity(0);
        when(cartRepository.findByIdForUpdate(5)).thenReturn(Optional.of(item.getCart()));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByIdForUpdate(99)).thenReturn(Optional.of(item));

        assertThrows(IllegalStateException.class,
                () -> orderService.createOrderFromCartItem(owner.getId(), 99));
        org.mockito.Mockito.verifyNoInteractions(orderRepository, orderItemRepository);
    }

    @Test
    void cartItemCheckoutRejectsEstimateForAnotherModel() {
        User owner = user(1);
        CartItem item = cartItem(owner, printer(10));
        item.getEstimate().setModel(Model3d.builder().id(4).build());
        when(cartRepository.findByIdForUpdate(5)).thenReturn(Optional.of(item.getCart()));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByIdForUpdate(99)).thenReturn(Optional.of(item));

        assertThrows(IllegalStateException.class,
                () -> orderService.createOrderFromCartItem(owner.getId(), 99));
        org.mockito.Mockito.verifyNoInteractions(orderRepository, orderItemRepository);
    }

    @Test
    void fullCartCheckoutRejectsWhenAnyEstimateIsExpiredBeforeWritingOrConsumingCart() {
        User owner = user(1);
        Printer printer = printer(10);
        Cart cart = new Cart();
        cart.setId(5);
        cart.setUser(owner);
        CartItem fresh = cartItem(owner, printer);
        fresh.setCart(cart);
        CartItem expired = cartItem(owner, printer);
        expired.setId(100);
        expired.setCart(cart);
        expired.getEstimate().setEstimatedAt(LocalDateTime.now().minusMinutes(16));
        when(cartRepository.findByUserIdForUpdate(owner.getId())).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCartIdForUpdate(cart.getId())).thenReturn(List.of(fresh, expired));

        assertThrows(OrderEstimateExpiredException.class, () -> orderService.createOrderFromCart(owner.getId()));

        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
        verify(cartItemRepository, never()).deleteAll(any());
        assertThat(fresh.getQuantity()).isEqualTo(2);
        assertThat(expired.getQuantity()).isEqualTo(2);
    }

    @Test
    void singleCartItemCheckoutRejectsExpiredEstimateBeforeWritingOrDeletingCartItem() {
        User owner = user(1);
        CartItem item = cartItem(owner, printer(10));
        item.getEstimate().setEstimatedAt(LocalDateTime.now().minusMinutes(16));
        when(cartRepository.findByIdForUpdate(5)).thenReturn(Optional.of(item.getCart()));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByIdForUpdate(99)).thenReturn(Optional.of(item));

        assertThrows(OrderEstimateExpiredException.class,
                () -> orderService.createOrderFromCartItem(owner.getId(), item.getId()));

        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
        verify(cartItemRepository, never()).delete(any());
        assertThat(item.getQuantity()).isEqualTo(2);
    }

    @Test
    void estimateFreshnessAcceptsAgeStrictlyUnderFifteenMinutes() {
        LocalDateTime checkoutTime = LocalDateTime.of(2026, 11, 6, 12, 0);
        PriceEstimation estimate = new PriceEstimation();
        estimate.setEstimatedAt(checkoutTime.minusMinutes(15).plusNanos(1));

        assertDoesNotThrow(() -> OrderServiceImpl.validateEstimateFreshness(estimate, checkoutTime));
    }

    @Test
    void estimateExactlyFifteenMinutesOldIsExpired() {
        LocalDateTime checkoutTime = LocalDateTime.of(2026, 11, 6, 12, 0);
        PriceEstimation estimate = new PriceEstimation();
        estimate.setEstimatedAt(checkoutTime.minusMinutes(15));

        assertThrows(OrderEstimateExpiredException.class,
                () -> OrderServiceImpl.validateEstimateFreshness(estimate, checkoutTime));
    }

    @Test
    void estimateOlderThanFifteenMinutesIsExpired() {
        LocalDateTime checkoutTime = LocalDateTime.of(2026, 11, 6, 12, 0);
        PriceEstimation estimate = new PriceEstimation();
        estimate.setEstimatedAt(checkoutTime.minusMinutes(15).minusNanos(1));

        assertThrows(OrderEstimateExpiredException.class,
                () -> OrderServiceImpl.validateEstimateFreshness(estimate, checkoutTime));
    }

    @Test
    void estimateWithoutTimestampIsExpired() {
        assertThrows(OrderEstimateExpiredException.class,
                () -> OrderServiceImpl.validateEstimateFreshness(new PriceEstimation(),
                        LocalDateTime.of(2026, 11, 6, 12, 0)));
    }

    @Test
    void estimateTimestampLaterThanCheckoutReferenceIsExpired() {
        LocalDateTime checkoutTime = LocalDateTime.of(2026, 11, 6, 12, 0);
        PriceEstimation estimate = new PriceEstimation();
        estimate.setEstimatedAt(checkoutTime.plusNanos(1));

        assertThrows(OrderEstimateExpiredException.class,
                () -> OrderServiceImpl.validateEstimateFreshness(estimate, checkoutTime));
    }

    @ParameterizedTest
    @MethodSource("approvedStatusTransitions")
    void updateOrderStatusAllowsOnlyApprovedTransitions(
            OrderStatus currentStatus,
            OrderStatus nextStatus) {
        LocalDateTime existingCompletedDate = LocalDateTime.of(2020, 1, 2, 3, 4);
        Order order = Order.builder()
                .id(12)
                .status(currentStatus)
                .completedDate(existingCompletedDate)
                .build();
        when(orderRepository.findByIdForUpdate(12)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderItemRepository.findAllByOrderId(12)).thenReturn(List.of());
        when(orderMapper.toResponse(order)).thenReturn(OrderResponse.builder()
                .id(12)
                .status(nextStatus)
                .build());
        LocalDateTime beforeUpdate = LocalDateTime.now();

        OrderResponse response = orderService.updateOrderStatus(12, request(nextStatus));

        LocalDateTime afterUpdate = LocalDateTime.now();
        assertThat(response.getStatus()).isEqualTo(nextStatus);
        assertThat(order.getStatus()).isEqualTo(nextStatus);
        if (nextStatus == OrderStatus.SUCCEEDED) {
            assertThat(order.getCompletedDate())
                    .isAfterOrEqualTo(beforeUpdate)
                    .isBeforeOrEqualTo(afterUpdate)
                    .isNotEqualTo(existingCompletedDate);
        } else {
            assertThat(order.getCompletedDate()).isEqualTo(existingCompletedDate);
        }
        verify(orderRepository).findByIdForUpdate(12);
        verify(orderRepository).save(order);
    }

    static Stream<Arguments> approvedStatusTransitions() {
        return Stream.of(
                Arguments.of(OrderStatus.PENDING, OrderStatus.PROCESSING),
                Arguments.of(OrderStatus.PENDING, OrderStatus.CANCELED),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.SUCCEEDED));
    }

    @ParameterizedTest
    @MethodSource("rejectedStatusTransitions")
    void updateOrderStatusRejectsEveryUnapprovedTransitionWithoutWriting(
            OrderStatus currentStatus,
            OrderStatus nextStatus) {
        LocalDateTime existingCompletedDate = LocalDateTime.of(2020, 1, 2, 3, 4);
        Order order = Order.builder()
                .id(12)
                .status(currentStatus)
                .completedDate(existingCompletedDate)
                .build();
        when(orderRepository.findByIdForUpdate(12)).thenReturn(Optional.of(order));
        if (currentStatus != OrderStatus.SUCCEEDED && currentStatus != OrderStatus.CANCELED) {
            lenient().when(orderRepository.save(order)).thenAnswer(invocation -> invocation.getArgument(0));
            lenient().when(orderItemRepository.findAllByOrderId(12)).thenReturn(List.of());
            lenient().when(orderMapper.toResponse(order)).thenReturn(OrderResponse.builder().id(12).build());
        }

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> orderService.updateOrderStatus(12, request(nextStatus)));

        assertThat(exception.getMessage()).isNotBlank();
        if (currentStatus == OrderStatus.PROCESSING && nextStatus == OrderStatus.CANCELED) {
            assertThat(exception.getMessage()).containsIgnoringCase("fulfillment");
        }
        assertThat(order.getStatus()).isEqualTo(currentStatus);
        assertThat(order.getCompletedDate()).isEqualTo(existingCompletedDate);
        verify(orderRepository).findByIdForUpdate(12);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderItemRepository, orderMapper, orderItemMapper);
    }

    static Stream<Arguments> rejectedStatusTransitions() {
        return Stream.of(
                Arguments.of(OrderStatus.PENDING, OrderStatus.PENDING),
                Arguments.of(OrderStatus.PENDING, OrderStatus.SUCCEEDED),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.PENDING),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.PROCESSING),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.CANCELED),
                Arguments.of(OrderStatus.SUCCEEDED, OrderStatus.PENDING),
                Arguments.of(OrderStatus.SUCCEEDED, OrderStatus.PROCESSING),
                Arguments.of(OrderStatus.SUCCEEDED, OrderStatus.SUCCEEDED),
                Arguments.of(OrderStatus.SUCCEEDED, OrderStatus.CANCELED),
                Arguments.of(OrderStatus.CANCELED, OrderStatus.PENDING),
                Arguments.of(OrderStatus.CANCELED, OrderStatus.PROCESSING),
                Arguments.of(OrderStatus.CANCELED, OrderStatus.SUCCEEDED),
                Arguments.of(OrderStatus.CANCELED, OrderStatus.CANCELED));
    }

    @Test
    void updateOrderStatusRejectsNullRequestBeforeReadingOrder() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.updateOrderStatus(12, null));

        verifyNoInteractions(orderRepository);
    }

    @Test
    void updateOrderStatusRejectsNullRequestedStatusBeforeReadingOrder() {
        Order order = Order.builder().id(12).status(OrderStatus.PENDING).build();
        lenient().when(orderRepository.findByIdForUpdate(12)).thenReturn(Optional.of(order));
        lenient().when(orderRepository.save(order)).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(orderItemRepository.findAllByOrderId(12)).thenReturn(List.of());
        lenient().when(orderMapper.toResponse(order)).thenReturn(OrderResponse.builder().id(12).build());

        assertThrows(IllegalArgumentException.class,
                () -> orderService.updateOrderStatus(12, request(null)));

        verifyNoInteractions(orderRepository);
    }

    @Test
    void updateOrderStatusRejectsOrderWithNullCurrentStatusWithoutWriting() {
        Order order = Order.builder().id(12).status(null).build();
        when(orderRepository.findByIdForUpdate(12)).thenReturn(Optional.of(order));
        lenient().when(orderRepository.save(order)).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(orderItemRepository.findAllByOrderId(12)).thenReturn(List.of());
        lenient().when(orderMapper.toResponse(order)).thenReturn(OrderResponse.builder().id(12).build());

        assertThrows(IllegalStateException.class,
                () -> orderService.updateOrderStatus(12, request(OrderStatus.PROCESSING)));

        assertThat(order.getStatus()).isNull();
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderItemRepository, orderMapper, orderItemMapper);
    }

    private UpdateOrderStatusRequest request(OrderStatus status) {
        UpdateOrderStatusRequest request = new UpdateOrderStatusRequest();
        request.setStatus(status);
        return request;
    }

    private User user(int id) {
        return User.builder()
                .id(id)
                .name("User " + id)
                .email("user" + id + "@example.com")
                .password("encoded")
                .build();
    }

    private Printer printer(int id) {
        return Printer.builder().id(id).name("Printer " + id).status(PrinterStatus.ACTIVE).build();
    }

    private CartItem cartItem(User owner, Printer printer) {
        Cart cart = new Cart();
        cart.setId(5);
        cart.setUser(owner);
        PriceEstimation estimate = new PriceEstimation();
        estimate.setPrinter(printer);
        estimate.setMaterial(Material.builder().id(20).name("Material").isAvailable(MaterialStatus.AVAILABLE).build());
        estimate.setTotalPrice(25.0);
        estimate.setTotalPriceVnd(25L);
        Model3d model = Model3d.builder().id(3).sourceType(ModelType.DESIGNER_MODEL)
                .status(ModelStatus.ACTIVE).build();
        estimate.setModel(model);
        estimate.setEstimatedAt(LocalDateTime.now().minusMinutes(1));
        CartItem item = new CartItem();
        item.setId(99);
        item.setCart(cart);
        item.setModel(model);
        item.setEstimate(estimate);
        item.setQuantity(2);
        return item;
    }
}
