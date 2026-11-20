package com.exe.morphia3d.feature;

import com.exe.morphia3d.controller.CartController;
import com.exe.morphia3d.controller.Model3dController;
import com.exe.morphia3d.controller.OrderController;
import com.exe.morphia3d.controller.PaymentController;
import com.exe.morphia3d.controller.PaymentApiExceptionHandler;
import com.exe.morphia3d.service.AIModelUploadService;
import com.exe.morphia3d.service.CartService;
import com.exe.morphia3d.service.DesignerModelUploadService;
import com.exe.morphia3d.service.Model3dService;
import com.exe.morphia3d.service.OrderService;
import com.exe.morphia3d.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.exe.morphia3d.dto.response.OrderResponse;
import com.exe.morphia3d.dto.request.UpdateOrderStatusRequest;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.security.UserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeatureGateFilterTest {
    private final PaymentService payment = mock(PaymentService.class);
    private final CartService cart = mock(CartService.class);
    private final OrderService order = mock(OrderService.class);
    private final AIModelUploadService aiUpload = mock(AIModelUploadService.class);
    private final Model3dService models = mock(Model3dService.class);
    private final DesignerModelUploadService designerUpload = mock(DesignerModelUploadService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        FeatureGate gate = new FeatureGate(new FeatureGateProperties());
        mvc = MockMvcBuilders.standaloneSetup(
                        new PaymentController(payment, gate),
                        new CartController(cart),
                        new OrderController(order),
                        new Model3dController(models, aiUpload, designerUpload),
                        new SafeRoutes())
                .setControllerAdvice(new PaymentApiExceptionHandler())
                .addFilters(new FeatureGateFilter(gate))
                .build();
    }

    @Test
    void disabledAnonymousPaymentEndpointsReturnNoStore503BeforeServiceCalls() throws Exception {
        mvc.perform(get("/api/payments/readiness").param("orderId", "5"))
                .andExpect(paymentDisabled());
        mvc.perform(post("/api/payments/attempts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-1").content("{\"orderId\":5,\"method\":\"MOMO\"}"))
                .andExpect(paymentDisabled());
        mvc.perform(get("/api/payments/attempts/f5ae8520-5472-4bc6-a6d4-c99cc86092ee"))
                .andExpect(paymentDisabled());
        mvc.perform(get("/api/payments/vnpay/return")).andExpect(status().isNotFound());
        mvc.perform(post("/api/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(payment);
    }

    @Test
    void disabledAiCartAndOrderRoutesStopBeforeControllers() throws Exception {
        mvc.perform(multipart("/api/ai-models").file(new MockMultipartFile("data", "{}".getBytes())))
                .andExpect(featureDisabled("AI"));
        mvc.perform(get("/api/ai-models/5")).andExpect(featureDisabled("AI"));
        mvc.perform(get("/api/cart")).andExpect(featureDisabled("CART"));
        mvc.perform(post("/api/orders")).andExpect(featureDisabled("ORDER"));
        mvc.perform(post("/api/orders/from-cart-item/1")).andExpect(featureDisabled("ORDER"));
        mvc.perform(get("/api/orders/1")).andExpect(featureDisabled("ORDER"));
        verifyNoInteractions(cart, order, aiUpload, models);
    }

    @Test
    void reservedQuoteAndCheckoutFamiliesRemainDisabled() throws Exception {
        mvc.perform(get("/api/quotes/1")).andExpect(featureDisabled("QUOTE"));
        mvc.perform(post("/api/price-estimates")).andExpect(featureDisabled("QUOTE"));
        mvc.perform(post("/api/checkout")).andExpect(featureDisabled("CHECKOUT"));
        mvc.perform(get("/api/checkouts/future/nested")).andExpect(featureDisabled("CHECKOUT"));
    }

    @Test
    void paymentRouteResolverGatesOnlyPaymentPathsAndLegacyCallbacksHaveNoRoute() throws Exception {
        FeatureGateProperties enabled = new FeatureGateProperties();
        enabled.getPayment().setEnabled(true);
        FeatureGate enabledGate = new FeatureGate(enabled);
        MockMvc paymentMvc = MockMvcBuilders.standaloneSetup(new SafeRoutes())
                .addFilters(new FeatureGateFilter(enabledGate)).build();
        assertThat(FeatureRouteResolver.requiredFamiliesFor("GET", "/api/payments/readiness"))
                .containsExactly(FeatureFamily.PAYMENT);
        paymentMvc.perform(get("/api/payments/momo/return")).andExpect(status().isNotFound());
        paymentMvc.perform(post("/api/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(payment);
    }

    @Test
    void enablingOneFamilyDoesNotEnableAnotherAndUnrelatedRoutesPassThrough() throws Exception {
        FeatureGateProperties onlyCart = new FeatureGateProperties();
        onlyCart.getCart().setEnabled(true);
        MockMvc isolated = MockMvcBuilders.standaloneSetup(new SafeRoutes())
                .addFilters(new FeatureGateFilter(new FeatureGate(onlyCart))).build();
        isolated.perform(get("/api/cart/diagnostic")).andExpect(status().isOk());
        isolated.perform(get("/api/orders/diagnostic")).andExpect(featureDisabled("ORDER"));

        for (String path : new String[]{"/api/products", "/api/admin/products", "/api/admin/staff",
                "/api/admin/tasks", "/api/v1/me/tasks", "/api/auth/ping"}) {
            isolated.perform(get(path)).andExpect(status().isOk());
        }
        verifyNoInteractions(cart, order, payment, aiUpload, models, designerUpload);
    }

    @Test
    void routeFamiliesUsePathBoundariesAndCoverFutureNestedRoutes() {
        assertThat(FeatureRouteResolver.familyFor("/api/ai-studio/generations/7"))
                .contains(FeatureFamily.AI);
        assertThat(FeatureRouteResolver.familyFor("/api/payments/future/nested"))
                .contains(FeatureFamily.PAYMENT);
        assertThat(FeatureRouteResolver.familyFor("/api/paymentsx")).isEmpty();
        assertThat(FeatureRouteResolver.familyFor("/api/admin/tasks/1")).isEmpty();
        assertThat(FeatureRouteResolver.familyFor("/api/products/1")).isEmpty();
        assertThat(FeatureRouteResolver.familyFor(null)).isEmpty();
    }

    @Test
    void checkoutCreationRoutesRequireBothOrderAndCheckoutGates() throws Exception {
        FeatureGateProperties properties = new FeatureGateProperties();
        properties.getOrder().setEnabled(true);
        FeatureGate gate = new FeatureGate(properties);
        MockMvc guarded = MockMvcBuilders.standaloneSetup(new OrderController(order))
                .addFilters(new FeatureGateFilter(gate)).build();

        guarded.perform(post("/api/orders").principal(authenticationFor(42)))
                .andExpect(featureDisabled("CHECKOUT"));
        guarded.perform(post("/api/orders/from-cart-item/9").principal(authenticationFor(42)))
                .andExpect(featureDisabled("CHECKOUT"));
        verifyNoInteractions(order);
    }

    @Test
    void checkoutRoutesProceedWhenBothGatesAreEnabledAndNonCreationRoutesNeedOnlyOrder()
            throws Exception {
        FeatureGateProperties properties = new FeatureGateProperties();
        properties.getOrder().setEnabled(true);
        properties.getCheckout().setEnabled(true);
        FeatureGate gate = new FeatureGate(properties);
        MockMvc guarded = MockMvcBuilders.standaloneSetup(new OrderController(order))
                .addFilters(new FeatureGateFilter(gate)).build();

        when(order.createOrderFromCart(42)).thenReturn(OrderResponse.builder().id(1).status(OrderStatus.PENDING).build());
        when(order.createOrderFromCartItem(42, 9)).thenReturn(OrderResponse.builder().id(2).status(OrderStatus.PENDING).build());
        when(order.getOrderById(42, 1)).thenReturn(OrderResponse.builder().id(1).status(OrderStatus.PENDING).build());
        when(order.updateOrderStatus(org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(UpdateOrderStatusRequest.class)))
                .thenReturn(OrderResponse.builder().id(1).status(OrderStatus.PROCESSING).build());

        guarded.perform(post("/api/orders").principal(authenticationFor(42))).andExpect(status().isCreated());
        guarded.perform(post("/api/orders/from-cart-item/9").principal(authenticationFor(42)))
                .andExpect(status().isCreated());
        guarded.perform(get("/api/orders/1").principal(authenticationFor(42))).andExpect(status().isOk());
        guarded.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PROCESSING\"}"))
                .andExpect(status().isOk());
        verify(order).createOrderFromCart(42);
        verify(order).createOrderFromCartItem(42, 9);
        verify(order).getOrderById(42, 1);
        verify(order).updateOrderStatus(org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(UpdateOrderStatusRequest.class));
    }

    @Test
    void orderReadAndStatusRoutesPassWhenCheckoutIsDisabled() throws Exception {
        FeatureGateProperties properties = new FeatureGateProperties();
        properties.getOrder().setEnabled(true);
        FeatureGate gate = new FeatureGate(properties);
        MockMvc guarded = MockMvcBuilders.standaloneSetup(new OrderController(order))
                .addFilters(new FeatureGateFilter(gate)).build();
        when(order.getOrderById(42, 1)).thenReturn(OrderResponse.builder().id(1).status(OrderStatus.PENDING).build());
        when(order.updateOrderStatus(org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(UpdateOrderStatusRequest.class)))
                .thenReturn(OrderResponse.builder().id(1).status(OrderStatus.PROCESSING).build());

        guarded.perform(get("/api/orders/1").principal(authenticationFor(42))).andExpect(status().isOk());
        guarded.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PROCESSING\"}"))
                .andExpect(status().isOk());
        verify(order).getOrderById(42, 1);
        verify(order).updateOrderStatus(org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(UpdateOrderStatusRequest.class));
    }

    @Test
    void checkoutCreationRoutesRemainBlockedWhenOrderIsOffEvenIfCheckoutIsOn() throws Exception {
        FeatureGateProperties properties = new FeatureGateProperties();
        properties.getCheckout().setEnabled(true);
        FeatureGate gate = new FeatureGate(properties);
        MockMvc guarded = MockMvcBuilders.standaloneSetup(new OrderController(order))
                .addFilters(new FeatureGateFilter(gate)).build();

        guarded.perform(post("/api/orders").principal(authenticationFor(42)))
                .andExpect(featureDisabled("ORDER"));
        guarded.perform(post("/api/orders/from-cart-item/9").principal(authenticationFor(42)))
                .andExpect(featureDisabled("ORDER"));
        verifyNoInteractions(order);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(Integer userId) {
        User user = User.builder().id(userId).name("QA").email("qa@example.test").password("encoded").build();
        UserPrincipal principal = new UserPrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private org.springframework.test.web.servlet.ResultMatcher featureDisabled(String family) {
        return result -> {
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("\"code\":\"FEATURE_DISABLED\"", "\"featureFamily\":\"" + family + "\"");
            assertThat(body).doesNotContain("stackTrace", "provider-secret", "database-url");
        };
    }

    private org.springframework.test.web.servlet.ResultMatcher paymentDisabled() {
        return result -> {
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"status\":503", "\"error\":\"PAYMENT_FEATURE_DISABLED\"");
        };
    }

    @RestController
    static class SafeRoutes {
        @RequestMapping({"/api/cart/diagnostic", "/api/orders/diagnostic", "/api/products",
                "/api/admin/products", "/api/admin/staff", "/api/admin/tasks", "/api/v1/me/tasks",
                "/api/auth/ping"})
        String ok() { return "ok"; }
    }
}
