package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import org.booklore.config.security.service.AuthRateLimitService;
import org.booklore.exception.APIException;
import org.booklore.model.entity.KoreaderUserEntity;
import org.booklore.repository.KoreaderUserRepository;
import org.booklore.service.koreader.KoreaderCredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KoreaderAuthFilterTest {

    @Mock private KoreaderUserRepository koreaderUserRepository;
    @Mock private KoreaderCredentialService koreaderCredentialService;
    @Mock private AuthRateLimitService authRateLimitService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void invalidPassword_recordsFailedIpAndUsernameAttempt() throws Exception {
        KoreaderAuthFilter filter = new KoreaderAuthFilter(koreaderUserRepository, koreaderCredentialService, authRateLimitService);
        MockHttpServletRequest request = request("reader", "bad-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);
        KoreaderUserEntity user = KoreaderUserEntity.builder().username("reader").passwordHash("hash").build();
        when(koreaderUserRepository.findByUsername("reader")).thenReturn(Optional.of(user));
        when(koreaderCredentialService.matches("bad-key", "hash")).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
        verify(authRateLimitService).recordFailedAlternateAuthAttempt("koreader", "192.0.2.10");
        verify(authRateLimitService).recordFailedAlternateAuthAttemptByCredential("koreader", "reader");
    }

    @Test
    void rateLimitedRequest_returnsTooManyRequestsBeforeLookup() throws Exception {
        KoreaderAuthFilter filter = new KoreaderAuthFilter(koreaderUserRepository, koreaderCredentialService, authRateLimitService);
        MockHttpServletRequest request = request("reader", "bad-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);
        doThrow(new APIException("too many", HttpStatus.TOO_MANY_REQUESTS))
                .when(authRateLimitService).checkAlternateAuthRateLimitByCredential("koreader", "reader");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(chainCalled).isFalse();
        verify(koreaderUserRepository, never()).findByUsername("reader");
    }

    private MockHttpServletRequest request(String username, String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/koreader/syncs/progress");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("x-auth-user", username);
        request.addHeader("x-auth-key", key);
        return request;
    }
}
