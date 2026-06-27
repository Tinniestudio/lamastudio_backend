package com.tinniestudio.api.shared.exception;

public class UpgradeRequiredException extends RuntimeException {
    public UpgradeRequiredException(String message) {
        super(message);
    }
}
