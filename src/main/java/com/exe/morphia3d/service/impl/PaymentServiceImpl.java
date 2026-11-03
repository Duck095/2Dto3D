package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.request.CreatePaymentRequest;
import com.exe.morphia3d.dto.response.PaymentUrlResponse;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.Payment;
import com.exe.morphia3d.enums.OrderStatus;
import com.exe.morphia3d.enums.PaymentStatus;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentRepository;
import com.exe.morphia3d.service.payment.PaymentGatewayService;
import com.exe.morphia3d.service.PaymentService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@Service
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentGatewayService vnPayService; // bean tên "vnPayService"
    private final PaymentGatewayService momoService;

    public PaymentServiceImpl(
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            @Qualifier("vnPayService") PaymentGatewayService vnPayService,
            @Qualifier("momoService") PaymentGatewayService momoService) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.vnPayService = vnPayService;
        this.momoService = momoService;
    }

    @Override
    @Transactional
    public PaymentUrlResponse createPayment(
            Integer authenticatedUserId,
            CreatePaymentRequest request,
            String ipAddress) {
        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new EntityNotFoundException("Order không tồn tại: " + request.getOrderId()));

        if (authenticatedUserId == null
                || order.getUser() == null
                || order.getUser().getId() == null
                || !Objects.equals(order.getUser().getId(), authenticatedUserId)) {
            throw new AccessDeniedException("Không thể thanh toán đơn hàng của người dùng khác");
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("Order không ở trạng thái chờ thanh toán");
        }

        // Tạo bản ghi Payment trước, trạng thái PENDING
        Payment payment = Payment.builder()
                .order(order)
                .amount(order.getTotalAmount())
                .method(request.getMethod())
                .build();
        payment = paymentRepository.save(payment);

        String paymentUrl = switch (request.getMethod()) {
            case VNPAY -> vnPayService.createPaymentUrl(order.getId(), order.getTotalAmount(), ipAddress);
            case MOMO -> momoService.createPaymentUrl(order.getId(), order.getTotalAmount(), ipAddress);
        };

        return PaymentUrlResponse.builder()
                .paymentUrl(paymentUrl)
                .paymentId(payment.getId())
                .build();
    }

    @Override
    @Transactional
    public void handleVnPayReturn(Map<String, String> params) {
        boolean isValid = vnPayService.verifyReturnUrl(params);
        if (!isValid) {
            throw new IllegalStateException("Chữ ký không hợp lệ, có thể dữ liệu bị giả mạo");
        }

        String txnRef = params.get("vnp_TxnRef");
        Integer orderId = Integer.valueOf(txnRef.split("_")[0]); // tách lại orderId đã gắn lúc tạo URL

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order không tồn tại: " + orderId));

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Payment không tồn tại cho order: " + orderId));

        applyCallbackResult(order, payment, "00".equals(params.get("vnp_ResponseCode")));
    }

    @Override
    @Transactional
    public void handleMomoReturn(Map<String, String> params) {
        boolean isValid = momoService.verifyReturnUrl(params);
        if (!isValid) {
            throw new IllegalStateException("Chữ ký MoMo không hợp lệ, có thể dữ liệu bị giả mạo");
        }

        String momoOrderId = params.get("orderId"); // dạng "{orderId}_{timestamp}"
        Integer orderId = Integer.valueOf(momoOrderId.split("_")[0]);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order không tồn tại: " + orderId));
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Payment không tồn tại cho order: " + orderId));

        applyCallbackResult(order, payment, "0".equals(params.get("resultCode")));
    }

    private void applyCallbackResult(Order order, Payment payment, boolean succeeded) {
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return;
        }

        if (succeeded) {
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setPaidAt(LocalDateTime.now());
            if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.CANCELED) {
                order.setStatus(OrderStatus.PROCESSING);
            }
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            if (order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.CANCELED);
            }
        }

        paymentRepository.save(payment);
        orderRepository.save(order);
    }
}
