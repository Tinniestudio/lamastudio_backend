package com.lamastudio.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
