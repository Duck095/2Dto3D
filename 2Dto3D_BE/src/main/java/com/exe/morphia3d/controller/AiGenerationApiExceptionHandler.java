package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.AiGenerationUnavailableResponse;
import com.exe.morphia3d.service.ai.AiGenerationException;
import com.exe.morphia3d.service.ai.AiGenerationUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = AiGenerationController.class)
public class AiGenerationApiExceptionHandler {
    @ExceptionHandler(AiGenerationUnavailableException.class)
    public ResponseEntity<AiGenerationUnavailableResponse> unavailable(AiGenerationUnavailableException failure) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, failure.getCode(), "AI generation is not configured.");
    }

    @ExceptionHandler(AiGenerationException.class)
    public ResponseEntity<AiGenerationUnavailableResponse> generation(AiGenerationException failure) {
        HttpStatus status = switch (failure.getCode()) {
            case "AI_GENERATION_NOT_FOUND", "AI_OWNER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "AI_IDEMPOTENCY_CONFLICT", "AI_RETRY_NOT_ALLOWED", "AI_RESULT_NOT_READY" -> HttpStatus.CONFLICT;
            case "AI_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "AI_AUTHENTICATION_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(status, failure.getCode(), publicMessage(failure.getCode()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, MissingServletRequestPartException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<AiGenerationUnavailableResponse> invalid(Exception failure) {
        return response(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST", "The generation request is invalid.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<AiGenerationUnavailableResponse> unsupportedMediaType(Exception failure) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "AI_UNSUPPORTED_MEDIA_TYPE",
                "The generation request media type is unsupported.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<AiGenerationUnavailableResponse> forbidden(AccessDeniedException failure) {
        return response(HttpStatus.FORBIDDEN, "AI_ACCESS_DENIED", "You do not have access to AI generation.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AiGenerationUnavailableResponse> unexpected(RuntimeException failure) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "AI_GENERATION_ERROR",
                "The generation request could not be completed.");
    }

    private ResponseEntity<AiGenerationUnavailableResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new AiGenerationUnavailableResponse(code, message, false));
    }

    private String publicMessage(String code) {
        return switch (code) {
            case "AI_GENERATION_NOT_FOUND" -> "The generation job was not found.";
            case "AI_IDEMPOTENCY_CONFLICT" -> "This idempotency key was already used for a different request.";
            case "AI_RATE_LIMITED" -> "Too many generation requests. Try again later.";
            case "AI_RETRY_NOT_ALLOWED" -> "Only failed generation jobs can be retried.";
            case "AI_RESULT_NOT_READY" -> "The generation result is not ready.";
            case "AI_IMAGE_REQUIRED" -> "An input image is required for image mode.";
            case "AI_IMAGE_TOO_LARGE" -> "The input image exceeds the allowed size.";
            case "AI_INVALID_IMAGE" -> "The input image is invalid.";
            default -> "The generation request is invalid.";
        };
    }
}
