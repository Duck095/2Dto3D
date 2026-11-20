package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.OrderApiErrorResponse;
import com.exe.morphia3d.service.OrderEstimateExpiredException;
import com.exe.morphia3d.service.OrderConfigurationUnavailableException;
import com.exe.morphia3d.service.OrderCartEmptyException;
import com.exe.morphia3d.service.OrderStatusRequestException;
import com.exe.morphia3d.service.OrderStatusTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderApiExceptionHandler {
    @ExceptionHandler(OrderCartEmptyException.class)
    ResponseEntity<OrderApiErrorResponse> handleEmptyCart(OrderCartEmptyException exception) {
        return error(HttpStatus.CONFLICT, "ORDER_CART_EMPTY", "The cart is empty and cannot be checked out");
    }

    @ExceptionHandler(OrderConfigurationUnavailableException.class)
    ResponseEntity<OrderApiErrorResponse> handleUnavailableConfiguration(
            OrderConfigurationUnavailableException exception) {
        return error(HttpStatus.CONFLICT, "ORDER_CONFIGURATION_UNAVAILABLE",
                "Model or print configuration is unavailable for checkout");
    }

    @ExceptionHandler(OrderEstimateExpiredException.class)
    ResponseEntity<OrderApiErrorResponse> handleExpiredEstimate(OrderEstimateExpiredException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new OrderApiErrorResponse(
                HttpStatus.CONFLICT.value(), "ESTIMATE_EXPIRED", exception.getMessage()));
    }

    @ExceptionHandler(OrderStatusTransitionException.class)
    ResponseEntity<OrderApiErrorResponse> handleInvalidTransition(OrderStatusTransitionException exception) {
        return error(HttpStatus.CONFLICT, "ORDER_STATUS_TRANSITION_NOT_ALLOWED",
                "Order status transition is not allowed");
    }

    @ExceptionHandler(OrderStatusRequestException.class)
    ResponseEntity<OrderApiErrorResponse> handleInvalidStatusRequest(OrderStatusRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ORDER_STATUS_REQUEST",
                "A valid order status is required");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<OrderApiErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ORDER_STATUS_REQUEST",
                "Request body or status is invalid");
    }

    private ResponseEntity<OrderApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new OrderApiErrorResponse(status.value(), code, message));
    }
}
