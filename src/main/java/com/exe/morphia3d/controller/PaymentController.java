package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.CreatePaymentRequest;
import com.exe.morphia3d.dto.response.PaymentUrlResponse;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentUrlResponse> createPayment(
            Authentication authentication,
            @RequestBody CreatePaymentRequest request,
            HttpServletRequest httpRequest) {
        Integer userId = currentUserId(authentication);
        String ipAddress = extractIp(httpRequest);
        PaymentUrlResponse response = paymentService.createPayment(userId, request, ipAddress);
        return ResponseEntity.ok(response);
    }

    // VNPay redirect user về đây sau khi thanh toán xong (GET request kèm query params)
    @GetMapping("/vnpay/return")
    public ResponseEntity<String> vnpayReturn(@RequestParam Map<String, String> allParams) {
        try {
            paymentService.handleVnPayReturn(allParams);
            // Thực tế nên redirect về trang FE, ví dụ:
            // return ResponseEntity.status(302).header("Location", "https://yourfrontend.com/payment-result?status=success").build();
            return ResponseEntity.ok("Thanh toán thành công");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Thanh toán thất bại: " + e.getMessage());
        }
    }

    // Redirect user về sau khi thanh toán trên app MoMo (GET, query params)
    @GetMapping("/momo/return")
    public ResponseEntity<String> momoReturn(@RequestParam Map<String, String> allParams) {
        try {
            paymentService.handleMomoReturn(allParams);
            return ResponseEntity.ok("Thanh toán thành công");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Thanh toán thất bại: " + e.getMessage());
        }
    }

    // MoMo gọi server-to-server để xác nhận kết quả (POST, JSON body) - đáng tin cậy hơn return URL
    @PostMapping("/momo/ipn")
    public ResponseEntity<Void> momoIpn(@RequestBody Map<String, String> body) {
        paymentService.handleMomoReturn(body);
        return ResponseEntity.ok().build(); // MoMo yêu cầu HTTP 204/200, không cần body
    }

    private String extractIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private Integer currentUserId(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)
                || principal.getUser() == null
                || principal.getId() == null) {
            throw new AccessDeniedException("Authenticated user principal is required");
        }
        return principal.getId();
    }
}
