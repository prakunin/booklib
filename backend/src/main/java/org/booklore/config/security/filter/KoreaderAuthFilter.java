package org.booklore.config.security.filter;

import org.booklore.config.security.userdetails.KoreaderUserDetails;
import org.booklore.repository.KoreaderUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthRateLimitService;
import org.booklore.exception.APIException;
import org.booklore.service.koreader.KoreaderCredentialService;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Component
@Slf4j
@FilterRegistration(enabled = false)
public class KoreaderAuthFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH = "koreader";

    private final KoreaderUserRepository koreaderUserRepository;
    private final KoreaderCredentialService koreaderCredentialService;
    private final AuthRateLimitService authRateLimitService;

    // Timing-equalization decoy for unknown users; random per process so it can't be precomputed.
    private String dummyPasswordHash;

    @PostConstruct
    void initDummyHash() {
        this.dummyPasswordHash = koreaderCredentialService.hashRawPassword(UUID.randomUUID().toString());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String username = request.getHeader("x-auth-user");
        String key = request.getHeader("x-auth-key");
        String ip = request.getRemoteAddr();

        if (isRateLimited(response, ip, username)) {
            return;
        }

        if (username == null || key == null) {
            log.info("Missing KOReader headers");
            recordFailedAttempt(ip, username);
            chain.doFilter(request, response);
            return;
        }

        var user = koreaderUserRepository.findByUsername(username).orElse(null);
        if (user == null) {
            koreaderCredentialService.matches(key, dummyPasswordHash);
            log.info("KOReader user not found");
            recordFailedAttempt(ip, username);
            chain.doFilter(request, response);
            return;
        }

        if (!koreaderCredentialService.matches(key, user.getPasswordHash())) {
            log.info("KOReader user password not match");
            recordFailedAttempt(ip, username);
            chain.doFilter(request, response);
            return;
        }
        if (koreaderCredentialService.isLegacyMd5Hash(user.getPasswordHash())) {
            user.setPasswordHash(koreaderCredentialService.hashWireKey(key));
            koreaderUserRepository.save(user);
        }

        Long bookLoreUserId = null;
        if (user.getBookLoreUser() != null) {
            bookLoreUserId = user.getBookLoreUser().getId();
        }

        UserDetails userDetails = new KoreaderUserDetails(
                user.getUsername(),
                key,
                user.isSyncEnabled(),
                user.isSyncWithWebReader(),
                bookLoreUserId,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        authRateLimitService.resetAlternateAuthAttempts(AUTH_PATH, ip);
        authRateLimitService.resetAlternateAuthAttemptsByCredential(AUTH_PATH, username);

        chain.doFilter(request, response);
    }

    private boolean isRateLimited(HttpServletResponse response, String ip, String username) throws IOException {
        try {
            authRateLimitService.checkAlternateAuthRateLimit(AUTH_PATH, ip);
            if (username != null) {
                authRateLimitService.checkAlternateAuthRateLimitByCredential(AUTH_PATH, username);
            }
            return false;
        } catch (APIException e) {
            response.sendError(e.getStatus().value(), e.getMessage());
            return true;
        }
    }

    private void recordFailedAttempt(String ip, String username) {
        authRateLimitService.recordFailedAlternateAuthAttempt(AUTH_PATH, ip);
        if (username != null) {
            authRateLimitService.recordFailedAlternateAuthAttemptByCredential(AUTH_PATH, username);
        }
    }
}
