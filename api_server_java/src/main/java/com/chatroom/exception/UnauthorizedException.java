package com.chatroom.exception;

/**
 * Thrown when authentication fails — wrong password or user not found.
 * Maps to HTTP 401 Unauthorized.
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}