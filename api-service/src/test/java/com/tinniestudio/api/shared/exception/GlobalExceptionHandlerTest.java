package com.tinniestudio.api.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: ResponseStatusException (thrown pervasively across content/season/episode/
 * upload/etc. services for 404/409/400 responses) used to be swallowed by the catch-all
 * Exception handler, which always returns 500 regardless of the exception's own status code.
 * The dedicated handleResponseStatusException() must intercept it first and preserve the
 * intended status.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServletPath("/contents/some-slug");
        return req;
    }

    @Test
    void responseStatusException_preservesNotFoundStatus() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: some-slug");

        ResponseEntity<Object> response = handler.handleResponseStatusException(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void responseStatusException_preservesConflictStatus() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.CONFLICT, "Content title conflict");

        ResponseEntity<Object> response = handler.handleResponseStatusException(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void genericException_stillFallsBackTo500() {
        Exception ex = new RuntimeException("boom");

        ResponseEntity<Object> response = handler.handleGeneral(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
