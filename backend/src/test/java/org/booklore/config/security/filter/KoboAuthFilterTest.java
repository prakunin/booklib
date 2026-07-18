package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import org.booklore.config.security.service.AuthRateLimitService;
import org.booklore.exception.APIException;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.KoboUserSettingsEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.repository.KoboUserSettingsRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.kobo.KoboTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KoboAuthFilterTest {

    @Mock private KoboUserSettingsRepository koboUserSettingsRepository;
    @Mock private UserRepository userRepository;
    @Mock private BookLoreUserTransformer bookLoreUserTransformer;
    @Mock private AuthRateLimitService authRateLimitService;
    @Mock private KoboTokenService koboTokenService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void invalidToken_recordsFailedIpAndTokenAttempt() throws Exception {
        KoboAuthFilter filter = new KoboAuthFilter(koboUserSettingsRepository, userRepository, bookLoreUserTransformer, authRateLimitService, koboTokenService);
        MockHttpServletRequest request = request("/api/kobo/bad-token/v1/initialization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};
        when(koboTokenService.hashToken("bad-token")).thenReturn("bad-hash");
        when(koboUserSettingsRepository.findByTokenHash("bad-hash")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(authRateLimitService).recordFailedAlternateAuthAttempt("kobo", "192.0.2.20");
        verify(authRateLimitService).recordFailedAlternateAuthAttemptByCredential("kobo", "bad-token");
    }

    @Test
    void validToken_resetsFailedIpAndTokenAttempts() throws Exception {
        KoboAuthFilter filter = new KoboAuthFilter(koboUserSettingsRepository, userRepository, bookLoreUserTransformer, authRateLimitService, koboTokenService);
        MockHttpServletRequest request = request("/api/kobo/good-token/v1/initialization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);
        BookLoreUserEntity entity = BookLoreUserEntity.builder()
                .id(7L)
                .permissions(UserPermissionsEntity.builder().permissionSyncKobo(true).build())
                .build();
        when(koboTokenService.hashToken("good-token")).thenReturn("good-hash");
        when(koboUserSettingsRepository.findByTokenHash("good-hash"))
                .thenReturn(Optional.of(KoboUserSettingsEntity.builder()
                        .userId(7L)
                        .tokenHash("good-hash")
                        .tokenExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS))
                        .build()));
        when(koboTokenService.isExpired(any())).thenReturn(false);
        when(userRepository.findByIdWithDetails(7L)).thenReturn(Optional.of(entity));
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(BookLoreUser.builder().id(7L).build());

        filter.doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_SYNC_KOBO");
        verify(authRateLimitService).resetAlternateAuthAttempts("kobo", "192.0.2.20");
        verify(authRateLimitService).resetAlternateAuthAttemptsByCredential("kobo", "good-token");
    }

    @Test
    void rateLimitedRequest_returnsTooManyRequestsBeforeTokenLookup() throws Exception {
        KoboAuthFilter filter = new KoboAuthFilter(koboUserSettingsRepository, userRepository, bookLoreUserTransformer, authRateLimitService, koboTokenService);
        MockHttpServletRequest request = request("/api/kobo/bad-token/v1/initialization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};
        doThrow(new APIException("too many", HttpStatus.TOO_MANY_REQUESTS))
                .when(authRateLimitService).checkAlternateAuthRateLimitByCredential("kobo", "bad-token");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(koboUserSettingsRepository, never()).findByTokenHash(any());
    }

    @Test
    void headerTokenTakesPrecedenceOverPathToken() throws Exception {
        KoboAuthFilter filter = new KoboAuthFilter(koboUserSettingsRepository, userRepository, bookLoreUserTransformer, authRateLimitService, koboTokenService);
        MockHttpServletRequest request = request("/api/kobo/path-token/v1/initialization");
        request.addHeader(KoboTokenService.HEADER_NAME, "header-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);
        BookLoreUserEntity entity = BookLoreUserEntity.builder()
                .id(7L)
                .permissions(UserPermissionsEntity.builder().permissionSyncKobo(true).build())
                .build();
        when(koboTokenService.hashToken("header-token")).thenReturn("header-hash");
        when(koboUserSettingsRepository.findByTokenHash("header-hash"))
                .thenReturn(Optional.of(KoboUserSettingsEntity.builder()
                        .userId(7L)
                        .tokenHash("header-hash")
                        .tokenExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS))
                        .build()));
        when(koboTokenService.isExpired(any())).thenReturn(false);
        when(userRepository.findByIdWithDetails(7L)).thenReturn(Optional.of(entity));
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(BookLoreUser.builder().id(7L).build());

        filter.doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
        verify(koboTokenService).hashToken("header-token");
        verify(koboTokenService, never()).hashToken("path-token");
    }

    @Test
    void expiredToken_returnsUnauthorized() throws Exception {
        KoboAuthFilter filter = new KoboAuthFilter(koboUserSettingsRepository, userRepository, bookLoreUserTransformer, authRateLimitService, koboTokenService);
        MockHttpServletRequest request = request("/api/kobo/expired-token/v1/initialization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};
        when(koboTokenService.hashToken("expired-token")).thenReturn("expired-hash");
        when(koboUserSettingsRepository.findByTokenHash("expired-hash"))
                .thenReturn(Optional.of(KoboUserSettingsEntity.builder()
                        .userId(7L)
                        .tokenHash("expired-hash")
                        .tokenExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
                        .build()));
        when(koboTokenService.isExpired(any())).thenReturn(true);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(userRepository, never()).findByIdWithDetails(any());
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("192.0.2.20");
        return request;
    }
}
