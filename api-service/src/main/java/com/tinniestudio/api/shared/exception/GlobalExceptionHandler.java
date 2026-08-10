package com.tinniestudio.api.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import com.tinniestudio.api.modules.auth.exception.BadCredentialsException;
import com.tinniestudio.api.modules.auth.exception.EmailAlreadyExistsException;
import com.tinniestudio.api.modules.auth.exception.InvalidEmailStateException;
import com.tinniestudio.api.modules.auth.exception.InvalidTokenException;
import com.tinniestudio.api.modules.auth.exception.MissingRefreshTokenException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Validation ────────────────────────────────────────────────────────────

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<Object> handleValidation(
                        MethodArgumentNotValidException ex,
                        HttpServletRequest request
        ) {
                Map<String, String> fieldErrors = new LinkedHashMap<>();
                for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
                        fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
                }

                Map<String, Object> body = buildStandardError("VALIDATION_FAILED",
                                "One or more fields failed validation",
                                HttpStatus.BAD_REQUEST.value(),
                                request.getServletPath());
                body.put("fieldErrors", fieldErrors);

                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

    // ── Malformed path variable / query param (e.g. "not-a-uuid" for a UUID path variable) ──
    // Without this handler, a client typo (bad UUID, non-numeric page size, etc.) was falling
    // through to the catch-all Exception handler below and returning a misleading 500
    // "unexpected error" instead of a 400 naming the actual bad parameter.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request
    ) {
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "the expected type";
        String message = "Parameter '" + ex.getName() + "' must be a valid " + requiredType
                + " (received: '" + ex.getValue() + "')";
        Map<String, Object> body = buildStandardError("INVALID_PARAMETER", message,
                HttpStatus.BAD_REQUEST.value(), request.getServletPath());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ── Method-level @Validated on @RequestParam/@PathVariable (not @Valid @RequestBody) ────
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.putIfAbsent(field, violation.getMessage());
        }
        Map<String, Object> body = buildStandardError("VALIDATION_FAILED",
                "One or more fields failed validation",
                HttpStatus.BAD_REQUEST.value(), request.getServletPath());
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ── Required @RequestParam missing entirely ──────────────────────────────
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> handleMissingParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing",
                HttpStatus.BAD_REQUEST.value(), request.getServletPath());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ── @ModelAttribute binding / query-param validation ─────────────────────

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Object> handleBindException(
            BindException ex,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = buildStandardError("VALIDATION_FAILED",
                "One or more fields failed validation",
                HttpStatus.BAD_REQUEST.value(),
                request.getServletPath());
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ── Domain exceptions ─────────────────────────────────────────────────────

        @ExceptionHandler(EmailAlreadyExistsException.class)
        public ResponseEntity<Object> handleEmailExists(
                        EmailAlreadyExistsException ex,
                        HttpServletRequest request
        ) {
                Map<String, Object> body = buildStandardError("EMAIL_ALREADY_EXISTS", ex.getMessage(), HttpStatus.CONFLICT.value(), request.getServletPath());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        @ExceptionHandler(AccountNotActiveException.class)
        public ResponseEntity<Object> handleAccountNotActive(
                        AccountNotActiveException ex,
                        HttpServletRequest request
        ) {
                Map<String, Object> body = buildStandardError("ACCOUNT_NOT_ACTIVE", ex.getMessage(), HttpStatus.FORBIDDEN.value(), request.getServletPath());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }

        @ExceptionHandler(InvalidTokenException.class)
        public ResponseEntity<Object> handleInvalidToken(
                        InvalidTokenException ex,
                        HttpServletRequest request
        ) {
                Map<String, Object> body = buildStandardError("INVALID_TOKEN", ex.getMessage(), HttpStatus.UNAUTHORIZED.value(), request.getServletPath());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("USER_NOT_FOUND", ex.getMessage(), HttpStatus.NOT_FOUND.value(), request.getServletPath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Object> handleBadCredentials(
            BadCredentialsException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("INVALID_CREDENTIALS", ex.getMessage(), HttpStatus.UNAUTHORIZED.value(), request.getServletPath());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Object> handleBadRequest(
            BadRequestException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("BAD_REQUEST", ex.getMessage(), HttpStatus.BAD_REQUEST.value(), request.getServletPath());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(InvalidEmailStateException.class)
    public ResponseEntity<Object> handleInvalidEmailState(
            InvalidEmailStateException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("INVALID_EMAIL_STATE", ex.getMessage(), HttpStatus.BAD_REQUEST.value(), request.getServletPath());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(EmailTokenExpiredException.class)
    public ResponseEntity<Object> handleEmailTokenExpired(
            EmailTokenExpiredException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("EMAIL_TOKEN_EXPIRED", ex.getMessage(), HttpStatus.BAD_REQUEST.value(), request.getServletPath());
        body.put("actionRequired", "resend_verification");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Object> handleRateLimitExceeded(
            RateLimitExceededException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("RATE_LIMIT_EXCEEDED", ex.getMessage(),
                HttpStatus.TOO_MANY_REQUESTS.value(), request.getServletPath());
        body.put("retryAfterSeconds", ex.getRetryAfterSeconds());
        body.put("maxRequests", ex.getMaxRequests());
        body.put("windowMinutes", ex.getWindowMinutes());

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .header("X-RateLimit-Limit", String.valueOf(ex.getMaxRequests()))
                .header("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() + (ex.getRetryAfterSeconds() * 1000L)))
                .body(body);
    }        @ExceptionHandler(MissingRefreshTokenException.class)
        public ResponseEntity<Object> handleMissingRefreshToken(
                        MissingRefreshTokenException ex,
                        HttpServletRequest request
        ) {
                Map<String, Object> body = buildStandardError("MISSING_REFRESH_TOKEN", ex.getMessage(), HttpStatus.UNAUTHORIZED.value(), request.getServletPath());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }

        @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
        public ResponseEntity<Object> handleSpringBadCredentials(
                        org.springframework.security.authentication.BadCredentialsException ex,
                        HttpServletRequest request
        ) {
                Map<String, Object> body = buildStandardError("INVALID_CREDENTIALS", "Invalid email or password", HttpStatus.UNAUTHORIZED.value(), request.getServletPath());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }

    @ExceptionHandler(UpgradeRequiredException.class)
    public ResponseEntity<Object> handleUpgradeRequired(
            UpgradeRequiredException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("UPGRADE_REQUIRED", ex.getMessage(),
                HttpStatus.FORBIDDEN.value(), request.getServletPath());
        body.put("reason", "upgrade_required");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        log.debug("Malformed request body at {}: {}", request.getServletPath(), ex.getMessage());
        Map<String, Object> body = buildStandardError("MALFORMED_REQUEST",
                "Request body is malformed or contains invalid characters",
                HttpStatus.BAD_REQUEST.value(), request.getServletPath());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ── Spring Security exceptions ────────────────────────────────────────────

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(
            AuthenticationException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("AUTHENTICATION_FAILED", "Authentication failed", HttpStatus.UNAUTHORIZED.value(), request.getServletPath());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

        @ExceptionHandler(AccessDeniedException.class)
        public ResponseEntity<Object> handleAccessDenied(
                        AccessDeniedException ex,
                        HttpServletRequest request
        ) {
                Map<String, Object> body = buildStandardError("ACCESS_DENIED", "You do not have permission to access this resource", HttpStatus.FORBIDDEN.value(), request.getServletPath());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }

    // ── ResponseStatusException (thrown pervasively across content/season/episode/etc.) ──────
    // Without this handler, the catch-all Exception handler below intercepts every
    // ResponseStatusException FIRST (our own @RestControllerAdvice runs ahead of Spring's
    // built-in ResponseStatusExceptionResolver), turning every intentional 404/409/400 thrown
    // via ResponseStatusException into a generic 500. This handler restores the exception's
    // own status code.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> handleResponseStatusException(
            ResponseStatusException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        Map<String, Object> body = buildStandardError(status.name(), message, status.value(), request.getServletPath());
        return ResponseEntity.status(status).body(body);
    }

    // ── Catch-all ─────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGeneral(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unhandled exception at {}: {}", request.getServletPath(), ex.getMessage(), ex);
        Map<String, Object> body = buildStandardError("INTERNAL_ERROR", "An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR.value(), request.getServletPath());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

        private Map<String, Object> buildStandardError(String code, String message, int status, String path) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", false);
                body.put("message", message);

                Map<String, String> err = new LinkedHashMap<>();
                err.put("code", code);
                err.put("message", message);

                body.put("error", err);
                body.put("status", status);
                body.put("path", path);
                body.put("timestamp", Instant.now().toString());
                return body;
        }
}
