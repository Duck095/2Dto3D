package com.exe.morphia3d.feature;

import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.enums.PaymentMethod;
import com.exe.morphia3d.dto.request.UpdateOrderStatusRequest;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.service.*;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.dto.response.OrderResponse;
import com.exe.morphia3d.service.impl.CartServiceImpl;
import com.exe.morphia3d.service.impl.Model3dServiceImpl;
import com.exe.morphia3d.service.impl.OrderServiceImpl;
import com.exe.morphia3d.service.impl.PaymentServiceImpl;
import com.exe.morphia3d.service.payment.PaymentGatewayService;
import com.exe.morphia3d.service.payment.MomoService;
import com.exe.morphia3d.service.payment.VnPayService;
import com.exe.morphia3d.mapper.ModelMapper;
import com.exe.morphia3d.mapper.OrderItemMapper;
import com.exe.morphia3d.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class FeatureGateServiceBoundaryTest {
    private final FeatureGate disabled = new FeatureGate(new FeatureGateProperties());

    @Test
    void paymentEntrypointsFailBeforePersistenceOrProviderCalls() {
        PaymentReadinessService readiness = mock(PaymentReadinessService.class);
        PaymentAttemptPersistenceService persistence = mock(PaymentAttemptPersistenceService.class);
        PaymentAttemptLifecycleService lifecycle = mock(PaymentAttemptLifecycleService.class);
        PaymentAttemptGateway vnPayAttemptGateway = mock(PaymentAttemptGateway.class);
        PaymentAttemptGateway momoAttemptGateway = mock(PaymentAttemptGateway.class);
        when(vnPayAttemptGateway.provider()).thenReturn(com.exe.morphia3d.enums.PaymentProvider.VNPAY);
        when(momoAttemptGateway.provider()).thenReturn(com.exe.morphia3d.enums.PaymentProvider.MOMO);
        PaymentServiceImpl service = new PaymentServiceImpl(disabled, readiness, persistence, lifecycle,
                List.of(vnPayAttemptGateway, momoAttemptGateway));

        assertThatThrownBy(() -> service.getReadiness(1, 8)).isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> service.createAttempt(1,
                new CreatePaymentAttemptRequest(8, PaymentMethod.VNPAY), "key-1"))
                .isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> service.getAttempt(1, java.util.UUID.randomUUID()))
                .isInstanceOf(FeatureDisabledException.class);
        verifyNoInteractions(readiness, persistence, lifecycle);
        verify(vnPayAttemptGateway).provider();
        verify(momoAttemptGateway).provider();
        verifyNoMoreInteractions(vnPayAttemptGateway, momoAttemptGateway);

        VnPayService vnPayAdapter = new VnPayService(null, null, disabled);
        MomoService momoAdapter = new MomoService(null, null, disabled);
        assertThatThrownBy(() -> vnPayAdapter.createPaymentUrl(1, 10d, "127.0.0.1"))
                .isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> vnPayAdapter.verifyReturnUrl(Map.of()))
                .isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> momoAdapter.createPaymentUrl(1, 10d, "127.0.0.1"))
                .isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> momoAdapter.verifyReturnUrl(Map.of()))
                .isInstanceOf(FeatureDisabledException.class);
    }

    @Test
    void cartAndOrderEntrypointsFailBeforeRepositoryCalls() {
        CartRepository carts = mock(CartRepository.class);
        CartItemRepository cartItems = mock(CartItemRepository.class);
        UserRepository users = mock(UserRepository.class);
        Model3dRepository models = mock(Model3dRepository.class);
        PriceEstimationRepository estimates = mock(PriceEstimationRepository.class);
        CartServiceImpl cart = new CartServiceImpl(carts, cartItems, users, models, estimates, disabled, 99);
        assertThatThrownBy(() -> cart.addItem(1, null)).isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> cart.getCart(1)).isInstanceOf(FeatureDisabledException.class);
        verifyNoInteractions(carts, cartItems, users, models, estimates);

        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository orderItems = mock(OrderItemRepository.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        OrderServiceImpl order = new OrderServiceImpl(orders, orderItems, carts, cartItems, users,
                orderMapper, orderItemMapper, disabled);
        assertThatThrownBy(() -> order.createOrderFromCart(1)).isInstanceOf(FeatureDisabledException.class);
        verifyNoInteractions(orders, orderItems, orderMapper, orderItemMapper);
    }

    @Test
    void checkoutServiceEntrypointsRequireOrderAndCheckoutBeforeRepositoryCalls() {
        FeatureGateProperties properties = new FeatureGateProperties();
        properties.getOrder().setEnabled(true);
        FeatureGate orderOnCheckoutOff = new FeatureGate(properties);
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository orderItems = mock(OrderItemRepository.class);
        CartRepository carts = mock(CartRepository.class);
        CartItemRepository cartItems = mock(CartItemRepository.class);
        UserRepository users = mock(UserRepository.class);
        OrderServiceImpl service = new OrderServiceImpl(orders, orderItems, carts, cartItems, users,
                mock(OrderMapper.class), mock(OrderItemMapper.class), orderOnCheckoutOff);

        assertThatThrownBy(() -> service.createOrderFromCart(1)).isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> service.createOrderFromCartItem(1, 2)).isInstanceOf(FeatureDisabledException.class);
        verifyNoInteractions(orders, orderItems, carts, cartItems, users);

        FeatureGateProperties checkoutOnlyProperties = new FeatureGateProperties();
        checkoutOnlyProperties.getCheckout().setEnabled(true);
        OrderServiceImpl orderOffCheckoutOn = new OrderServiceImpl(orders, orderItems, carts, cartItems, users,
                mock(OrderMapper.class), mock(OrderItemMapper.class), new FeatureGate(checkoutOnlyProperties));
        assertThatThrownBy(() -> orderOffCheckoutOn.createOrderFromCart(1))
                .isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> orderOffCheckoutOn.createOrderFromCartItem(1, 2))
                .isInstanceOf(FeatureDisabledException.class);
        verifyNoInteractions(orders, orderItems, carts, cartItems, users);
    }

    @Test
    void orderReadAndStatusOperationsRemainAvailableWhenCheckoutIsDisabled() {
        FeatureGateProperties properties = new FeatureGateProperties();
        properties.getOrder().setEnabled(true);
        FeatureGate orderOnly = new FeatureGate(properties);
        OrderRepository orders = mock(OrderRepository.class);
        OrderItemRepository items = mock(OrderItemRepository.class);
        CartRepository carts = mock(CartRepository.class);
        CartItemRepository cartItems = mock(CartItemRepository.class);
        UserRepository users = mock(UserRepository.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        Order order = Order.builder().id(7).user(User.builder().id(3).build()).status(OrderStatus.PENDING).build();
        when(orders.findById(7)).thenReturn(Optional.of(order));
        when(orders.findByIdForUpdate(7)).thenReturn(Optional.of(order));
        when(orders.save(order)).thenReturn(order);
        when(items.findAllByOrderId(7)).thenReturn(List.of());
        when(orderMapper.toResponse(order)).thenReturn(OrderResponse.builder().id(7).status(OrderStatus.PENDING).build());
        OrderServiceImpl service = new OrderServiceImpl(orders, items, carts, cartItems, users,
                orderMapper, mock(OrderItemMapper.class), orderOnly);

        service.getOrderById(3, 7);
        UpdateOrderStatusRequest request = new UpdateOrderStatusRequest();
        request.setStatus(OrderStatus.PROCESSING);
        service.updateOrderStatus(7, request);

        verify(orders).findById(7);
        verify(orders).findByIdForUpdate(7);
        verify(orders).save(order);
    }

    @Test
    void aiUploadPersistenceAndPrivateReadFailBeforeMediaOrDatabaseCalls() {
        MediaValidationService validation = mock(MediaValidationService.class);
        MediaStorageService storage = mock(MediaStorageService.class);
        MediaAssetLifecycleService lifecycle = mock(MediaAssetLifecycleService.class);
        MediaUploadHeartbeat heartbeat = mock(MediaUploadHeartbeat.class);
        AIModelPersistenceService persistence = mock(AIModelPersistenceService.class);
        AIModelUploadService upload = new AIModelUploadService(validation, storage, lifecycle, heartbeat,
                persistence, disabled);
        assertThatThrownBy(() -> upload.create(null, null, null, null))
                .isInstanceOf(FeatureDisabledException.class);
        verifyNoInteractions(validation, storage, lifecycle, heartbeat, persistence);

        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        Model3dRepository models = mock(Model3dRepository.class);
        UserRepository users = mock(UserRepository.class);
        ColorRepository colors = mock(ColorRepository.class);
        ModelMapper mapper = mock(ModelMapper.class);
        AIModelPersistenceService persist = new AIModelPersistenceService(transactions, assets, models, users,
                colors, mapper, lifecycle, disabled);
        assertThatThrownBy(() -> persist.persist(null, null, null))
                .isInstanceOf(FeatureDisabledException.class);
        verifyNoInteractions(transactions, assets, models, users, colors, mapper);

        com.exe.morphia3d.service.admin3d.Admin3dRestrictedMutationGateway commands =
                mock(com.exe.morphia3d.service.admin3d.Admin3dRestrictedMutationGateway.class);
        Model3dServiceImpl modelService = new Model3dServiceImpl(models, mapper, disabled, commands);
        assertThatThrownBy(() -> modelService.getAIModelById(1, 1))
                .isInstanceOf(FeatureDisabledException.class);
        assertThatThrownBy(() -> modelService.getAllModelsBySourceType(com.exe.morphia3d.enums.ModelType.AI_MODEL))
                .isInstanceOf(FeatureDisabledException.class);
        verifyNoInteractions(models, mapper, commands);
    }
}
