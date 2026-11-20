package com.exe.morphia3d.controller;
import com.exe.morphia3d.dto.response.ProductApiErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(assignableTypes={AdminTaskController.class,MyTaskController.class})
public class TaskApiExceptionHandler {
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<ProductApiErrorResponse> status(ResponseStatusException e){HttpStatus s=HttpStatus.valueOf(e.getStatusCode().value());return response(s,e.getReason()==null?s.getReasonPhrase():e.getReason());}
 @ExceptionHandler({MethodArgumentNotValidException.class,ConstraintViolationException.class,MethodArgumentTypeMismatchException.class,HandlerMethodValidationException.class,HttpMessageNotReadableException.class}) ResponseEntity<ProductApiErrorResponse> invalid(Exception e){return response(HttpStatus.BAD_REQUEST,"Invalid task request");}
 @ExceptionHandler({DataIntegrityViolationException.class,ObjectOptimisticLockingFailureException.class}) ResponseEntity<ProductApiErrorResponse> conflict(Exception e){return response(HttpStatus.CONFLICT,"Task changed concurrently or violates a task constraint");}
 private ResponseEntity<ProductApiErrorResponse> response(HttpStatus s,String message){return ResponseEntity.status(s).body(new ProductApiErrorResponse(s.value(),s.getReasonPhrase(),message));}
}
