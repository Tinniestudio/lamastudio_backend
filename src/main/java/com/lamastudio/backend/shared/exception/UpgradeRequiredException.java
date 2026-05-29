package com.lamastudio.backend.shared.exception;

public class UpgradeRequiredException extends RuntimeException {
    public UpgradeRequiredException(String message) {
        super(message);
    }
}
