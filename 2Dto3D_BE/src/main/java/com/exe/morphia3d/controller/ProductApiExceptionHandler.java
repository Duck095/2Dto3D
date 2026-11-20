package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.ProductApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = ProductController.class)
public class ProductApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProductApiErrorResponse> handleStatusException(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(status).body(new ProductApiErrorResponse(
                status.value(), status.getReasonPhrase(), exception.getReason()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProductApiErrorResponse> handleInvalidParameter(Exception exception) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = exception instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value for parameter '" + mismatch.getName() + "'"
                : "Required request parameter is missing";
        return ResponseEntity.status(status).body(new ProductApiErrorResponse(
                status.value(), status.getReasonPhrase(), message));
    }
}
