package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.ProductApiErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminProductPrintAssetController.class)
public class AdminProductPrintAssetExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<ProductApiErrorResponse> notFound(EntityNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ProductApiErrorResponse(
                HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.getReasonPhrase(),
                "An Admin 3D product was not found"));
    }
}
