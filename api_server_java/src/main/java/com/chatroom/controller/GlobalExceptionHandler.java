package com.chatroom.controller;

import com.chatroom.exception.NotFoundException;
import com.chatroom.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles business logic errors such as duplicate username or wrong password.
     * Returns 400 with the error message.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBusiness(IllegalArgumentException e) {
        log.warn("Business rule rejection: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /**
     * Handles @Valid validation failures (blank fields, format errors etc.).
     * Returns 400 with the first validation error message.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .get(0)
                .getDefaultMessage();
        log.warn("Validation failed: {}", message);
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /**
     * Catches all unexpected exceptions and returns a generic 500 response.
     * Prevents internal error details from leaking to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception e) {
        log.error("Unexpected error handling request", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "internal server error"));
    }

    /**
     * Handles authentication failures such as wrong password or unknown user.
     * Returns 401 Unauthorized.
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<?> handleUnauthorized(UnauthorizedException e) {
        log.warn("Unauthorized: {}", e.getMessage());
        return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    }

    /**
     * Handles resource-not-found errors such as a missing chatroom.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> handleNotFound(NotFoundException e) {
        log.warn("Not found: {}", e.getMessage());
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }
}
