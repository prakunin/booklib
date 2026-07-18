package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import org.booklore.config.security.service.AuthRateLimitService;
import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.exception.APIException;
import org.booklore.model.dto.OpdsUserV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OpdsBasicAuthRateLimitFilterTest {

    @Mock private AuthRateLimitService authRateLimitService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthorizedBasicResponse_recordsFailedIpAndUsernameAttempt() throws Exception {
        OpdsBasicAuthRateLimitFilter filter = new OpdsBasicAuthRateLimitFilter(authRateLimitService);
        MockHttpServletRequest request = request("reader", "bad-password");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> response.setStatus(401);

        filter.doFilter(request, response, chain);

        verify(authRateLimitService).recordFailedAlternateAuthAttempt("opds", "192.0.2.30");
        verify(authRateLimitService).recordFailedAlternateAuthAttemptByCredential("opds", "reader");
    }

    @Test
    void successfulOpdsBasicAuthentication_resetsFailedIpAndUsernameAttempts() throws Exception {
        OpdsBasicAuthRateLimitFilter filter = new OpdsBasicAuthRateLimitFilter(authRateLimitService);
        MockHttpServletRequest request = request("reader", "password");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OpdsUserDetails principal = new OpdsUserDetails(OpdsUserV2.builder().username("reader").build());
        FilterChain chain = (req, res) -> SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of())
        );

        filter.doFilter(request, response, chain);

        verify(authRateLimitService).resetAlternateAuthAttempts("opds", "192.0.2.30");
        verify(authRateLimitService).resetAlternateAuthAttemptsByCredential("opds", "reader");
    }

    @Test
    void rateLimitedBasicRequest_returnsTooManyRequestsBeforeAuthChain() throws Exception {
        OpdsBasicAuthRateLimitFilter filter = new OpdsBasicAuthRateLimitFilter(authRateLimitService);
        MockHttpServletRequest request = request("reader", "bad-password");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);
        doThrow(new APIException("too many", HttpStatus.TOO_MANY_REQUESTS))
                .when(authRateLimitService).checkAlternateAuthRateLimitByCredential("opds", "reader");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(chainCalled).isFalse();
    }

    private MockHttpServletRequest request(String username, String password) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/opds/catalog");
        request.setRemoteAddr("192.0.2.30");
        String header = username + ":" + password;
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(header.getBytes(StandardCharsets.UTF_8)));
        return request;
    }
}
