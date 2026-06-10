package com.lamastudio.backend.shared.web;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;

/**
 * Wraps every 2xx JSON response in the standard envelope
 * {@code { success, message, data, meta? }}.
 *
 * <p>Skipped for:
 * <ul>
 *   <li>Non-Jackson converters (byte arrays, plain text, multipart — not JSON responses)</li>
 *   <li>Controllers annotated with {@link SkipResponseWrapper}</li>
 *   <li>{@link ControllerAdvice}/{@link RestControllerAdvice} classes (exception handlers)</li>
 *   <li>Return types already declared as {@link ApiResponse}</li>
 *   <li>Null bodies (preserves 204 No Content semantics)</li>
 *   <li>Bodies that are already an {@link ApiResponse} instance (idempotency)</li>
 * </ul>
 */
@RestControllerAdvice
public class SuccessResponseWrapper implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        // Only wrap Jackson JSON responses — byte[], String, and other converters are excluded
        if (!AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType)) {
            return false;
        }
        Class<?> declaringClass = returnType.getDeclaringClass();
        if (declaringClass.isAnnotationPresent(SkipResponseWrapper.class)) {
            return false;
        }
        // Skip exception handlers — they produce error responses, not success envelopes
        if (declaringClass.isAnnotationPresent(ControllerAdvice.class)
                || declaringClass.isAnnotationPresent(RestControllerAdvice.class)) {
            return false;
        }
        // Skip springdoc internal controllers (swagger-config, api-docs) — wrapping breaks Swagger UI
        if (declaringClass.getPackageName().startsWith("org.springdoc")) {
            return false;
        }
        Class<?> rawType = returnType.getParameterType();
        if (ApiResponse.class.isAssignableFrom(rawType)) {
            return false;
        }
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null) {
            return null;
        }
        if (body instanceof ApiResponse) {
            return body;
        }

        String message;
        Object data;

        if (body instanceof Map<?, ?> map
                && map.size() == 1
                && map.containsKey("message")
                && map.get("message") instanceof String s) {
            message = s;
            data = null;
        } else {
            message = defaultMessage(request.getMethod());
            data = body;
        }

        return ApiResponse.ok(message, data);
    }

    private static String defaultMessage(HttpMethod method) {
        if (method == null) return "Success";
        return switch (method.name()) {
            case "POST"         -> "Created successfully";
            case "PATCH", "PUT" -> "Updated successfully";
            case "DELETE"       -> "Deleted successfully";
            default             -> "Retrieved successfully";
        };
    }
}
