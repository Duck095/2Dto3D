package com.exe.morphia3d.controller;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MaterialController.class)
public class MaterialApiExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<Void> handleNotFound(EntityNotFoundException exception) {
        return ResponseEntity.notFound().build();
    }
}
