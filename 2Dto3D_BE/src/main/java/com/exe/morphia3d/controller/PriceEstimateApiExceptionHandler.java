package com.exe.morphia3d.controller;

import com.exe.morphia3d.service.pricing.PricingNotConfiguredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = PriceEstimateController.class)
public class PriceEstimateApiExceptionHandler {
    @ExceptionHandler(PricingNotConfiguredException.class)
    ResponseEntity<Map<String, String>> handlePricingNotConfigured(PricingNotConfiguredException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "PRICING_NOT_CONFIGURED", "missing", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleInvalidConfiguration(IllegalArgumentException exception) {
        String reason = exception.getMessage() == null ? "PRINT_CONFIGURATION_UNAVAILABLE" : exception.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", reason));
    }
}
