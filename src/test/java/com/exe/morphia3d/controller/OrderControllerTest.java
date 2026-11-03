package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.OrderResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    private OrderService orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService)).build();
    }

    @Test
    void createOrderUsesAuthenticatedPrincipalIdInsteadOfParsingEmail() {
        User user = User.builder()
                .id(42)
                .name("Customer")
                .email("customer@example.com")
                .password("encoded")
                .build();
        UserPrincipal principal = new UserPrincipal(user);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        when(orderService.createOrderFromCart(42)).thenReturn(OrderResponse.builder()
                .id(7)
                .status(OrderStatus.PENDING)
                .totalAmount(12.0)
                .build());

        assertDoesNotThrow(() -> mockMvc.perform(post("/api/orders").principal(authentication))
                .andExpect(status().isCreated()));

        verify(orderService).createOrderFromCart(42);
    }
}
