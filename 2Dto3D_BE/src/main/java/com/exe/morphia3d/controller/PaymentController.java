package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.dto.response.PaymentAttemptResponse;
import com.exe.morphia3d.dto.response.PaymentReadinessResponse;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.PaymentApiException;
import com.exe.morphia3d.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;
    private final FeatureGate featureGate;

    @GetMapping("/readiness")
    public ResponseEntity<PaymentReadinessResponse> readiness(
            Authentication authentication,
            @RequestParam @Positive Integer orderId) {
        featureGate.requireEnabled(FeatureFamily.PAYMENT);
        return ResponseEntity.ok(paymentService.getReadiness(currentUserId(authentication), orderId));
    }

    @PostMapping("/attempts")
    public ResponseEntity<PaymentAttemptResponse> createAttempt(
            Authentication authentication,
            @Valid @RequestBody CreatePaymentAttemptRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        featureGate.requireEnabled(FeatureFamily.PAYMENT);
        return ResponseEntity.ok(paymentService.createAttempt(currentUserId(authentication), request, idempotencyKey));
    }

    @GetMapping("/attempts/{attemptId}")
    public ResponseEntity<PaymentAttemptResponse> getAttempt(
            Authentication authentication,
            @PathVariable UUID attemptId) {
        featureGate.requireEnabled(FeatureFamily.PAYMENT);
        return ResponseEntity.ok(paymentService.getAttempt(currentUserId(authentication), attemptId));
    }

    private Integer currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)
                || principal.getId() == null) {
            throw new PaymentApiException(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
        }
        return principal.getId();
    }
}
