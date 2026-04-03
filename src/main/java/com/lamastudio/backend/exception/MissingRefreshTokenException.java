package com.lamastudio.backend.exception;

public class MissingRefreshTokenException extends RuntimeException {
    public MissingRefreshTokenException(String message) {
        super(message);
    }
}
