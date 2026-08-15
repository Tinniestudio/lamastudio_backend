package com.tinniestudio.api.modules.auth.exception;

import com.tinniestudio.api.shared.exception.BadRequestException;

/**
 * Exception thrown when email is in an invalid state for the requested operation.
 * For example: trying to resend verification to an already verified email.
 */
public class InvalidEmailStateException extends BadRequestException {
    public InvalidEmailStateException(String message) {
        super(message);
    }
}
