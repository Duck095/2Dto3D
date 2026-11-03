package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.response.OrderItemResponse;
import com.exe.morphia3d.dto.response.OrderResponse;
import com.exe.morphia3d.entity.Cart;
import com.exe.morphia3d.entity.CartItem;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.PriceEstimation;
import com.exe.morphia3d.entity.Printer;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.mapper.OrderItemMapper;
import com.exe.morphia3d.mapper.OrderMapper;
import com.exe.morphia3d.repository.CartItemRepository;
import com.exe.morphia3d.repository.CartRepository;
import com.exe.morphia3d.repository.OrderItemRepository;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PrinterRepository;
import com.exe.morphia3d.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    @InjectMocks private OrderServiceImpl orderService;

    @Test
    void cartItemCheckoutRejectsAnotherUsersItem() {
        User owner = user(1);
        User attacker = user(2);
        CartItem foreignItem = cartItem(owner, printer(10));
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(foreignItem));

        assertThrows(AccessDeniedException.class,
                () -> orderService.createOrderFromCartItem(attacker.getId(), 99));
    }

    @Test
    void cartItemCheckoutCopiesPrinterFromPriceEstimation() {
        User owner = user(1);
        Printer expectedPrinter = printer(10);
        CartItem item = cartItem(owner, expectedPrinter);
        when(cartItemRepository.findById(99)).thenReturn(Optional.of(item));
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
        when(cartRepository.findByUserId(owner.getId())).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCartId(cart.getId())).thenReturn(List.of(first, second));
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
        when(cartRepository.findByUserId(owner.getId())).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCartId(cart.getId())).thenReturn(List.of(first, second));
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
    void orderReadRejectsAnotherUser() {
        Order order = Order.builder().id(12).user(user(1)).build();
        when(orderRepository.findById(12)).thenReturn(Optional.of(order));

        assertThrows(AccessDeniedException.class,
                () -> orderService.getOrderById(2, 12));
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
        return Printer.builder().id(id).name("Printer " + id).build();
    }

    private CartItem cartItem(User owner, Printer printer) {
        Cart cart = new Cart();
        cart.setId(5);
        cart.setUser(owner);
        PriceEstimation estimate = new PriceEstimation();
        estimate.setPrinter(printer);
        estimate.setTotalPrice(25.0);
        CartItem item = new CartItem();
        item.setId(99);
        item.setCart(cart);
        item.setModel(Model3d.builder().id(3).build());
        item.setEstimate(estimate);
        item.setQuantity(2);
        return item;
    }
}
