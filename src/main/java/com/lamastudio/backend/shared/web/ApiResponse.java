package com.lamastudio.backend.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard envelope for all 2xx JSON responses.
 * Error responses are handled separately by GlobalExceptionHandler.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        Meta meta
) {

    public record Meta(long total, int page, int size, int totalPages) {}

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, null);
    }

    public static <T> ApiResponse<T> ok(String message, T data, Meta meta) {
        return new ApiResponse<>(true, message, data, meta);
    }
}
