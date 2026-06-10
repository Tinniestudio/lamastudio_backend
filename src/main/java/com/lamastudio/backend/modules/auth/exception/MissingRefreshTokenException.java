package com.lamastudio.backend.modules.auth.exception;

public class MissingRefreshTokenException extends RuntimeException {
    public MissingRefreshTokenException(String message) {
        super(message);
    }
}
