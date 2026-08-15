package com.tinniestudio.api.shared.exception;

/**
 * Exception thrown when email verification token is expired.
 */
public class EmailTokenExpiredException extends BadRequestException {
    public EmailTokenExpiredException(String message) {
        super(message);
    }
}
