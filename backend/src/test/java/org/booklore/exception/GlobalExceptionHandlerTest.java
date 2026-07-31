package org.booklore.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
    }

    @Nested
    @DisplayName("Unsupported HTTP method")
    class UnsupportedHttpMethod {

        @Test
        void respondsWithMethodNotAllowedInsteadOfServerError() {
            when(request.getMethod()).thenReturn("PUT");
            when(request.getRequestURI()).thenReturn("/library");

            ResponseEntity<ErrorResponse> response = handler.handleHttpRequestMethodNotSupported(
                    new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "HEAD")), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        }

        @Test
        void advertisesSupportedMethodsInAllowHeader() {
            when(request.getMethod()).thenReturn("PUT");
            when(request.getRequestURI()).thenReturn("/library");

            ResponseEntity<ErrorResponse> response = handler.handleHttpRequestMethodNotSupported(
                    new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "HEAD")), request);

            assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.HEAD);
        }

        @Test
        void omitsAllowHeaderWhenNoSupportedMethodsAreKnown() {
            when(request.getMethod()).thenReturn("PUT");
            when(request.getRequestURI()).thenReturn("/library");

            ResponseEntity<ErrorResponse> response = handler.handleHttpRequestMethodNotSupported(
                    new HttpRequestMethodNotSupportedException("PUT"), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getHeaders().containsHeader(HttpHeaders.ALLOW)).isFalse();
        }
    }
}
