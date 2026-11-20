package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.CartApiErrorResponse;
import com.exe.morphia3d.service.CartApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = CartController.class)
public class CartApiExceptionHandler {
    @ExceptionHandler(CartApiException.class)
    ResponseEntity<CartApiErrorResponse> handleCartException(CartApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new CartApiErrorResponse(
                exception.getStatus().value(), exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<CartApiErrorResponse> handleInvalidRequest(MethodArgumentNotValidException exception) {
        String field = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField())
                .orElse("request");
        return error(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED",
                "Invalid or missing value for '" + field + "'");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<CartApiErrorResponse> handleUnreadableRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED", "Request body or path value is invalid");
    }

    private ResponseEntity<CartApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new CartApiErrorResponse(status.value(), code, message));
    }
}
