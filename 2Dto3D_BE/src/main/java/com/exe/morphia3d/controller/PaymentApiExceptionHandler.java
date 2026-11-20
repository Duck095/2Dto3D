package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.PaymentApiErrorResponse;
import com.exe.morphia3d.feature.FeatureDisabledException;
import com.exe.morphia3d.service.PaymentApiException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice(assignableTypes = PaymentController.class)
public class PaymentApiExceptionHandler {
    @ExceptionHandler(PaymentApiException.class)
    ResponseEntity<PaymentApiErrorResponse> handlePayment(PaymentApiException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatus());
        return response(status, exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(FeatureDisabledException.class)
    ResponseEntity<PaymentApiErrorResponse> handleDisabled(FeatureDisabledException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_FEATURE_DISABLED",
                "Payment is unavailable");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<PaymentApiErrorResponse> handleForbidden(AccessDeniedException exception) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access to this payment resource is not allowed");
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<PaymentApiErrorResponse> handleNotFound(EntityNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "PAYMENT_RESOURCE_NOT_FOUND", "Payment resource was not found");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<PaymentApiErrorResponse> handleConflict(DataIntegrityViolationException exception) {
        return response(HttpStatus.CONFLICT, "PAYMENT_CONCURRENT_CONFLICT",
                "Payment state changed concurrently; retrieve the current attempt before retrying");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, HandlerMethodValidationException.class})
    ResponseEntity<PaymentApiErrorResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "PAYMENT_REQUEST_INVALID", "Payment request is invalid");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<PaymentApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, "PAYMENT_REQUEST_INVALID", "Payment request is invalid");
    }

    private ResponseEntity<PaymentApiErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new PaymentApiErrorResponse(status.value(), code, message));
    }
}
