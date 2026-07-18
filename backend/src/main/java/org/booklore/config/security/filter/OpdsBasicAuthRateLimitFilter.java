package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthRateLimitService;
import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.exception.APIException;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@RequiredArgsConstructor
@Component
@Slf4j
@FilterRegistration(enabled = false)
public class OpdsBasicAuthRateLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH = "opds";
    private static final String BASIC_PREFIX = "Basic ";

    private final AuthRateLimitService authRateLimitService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        BasicCredentials credentials = parseBasicCredentials(request.getHeader("Authorization"));
        String ip = request.getRemoteAddr();

        if (isRateLimited(response, ip, credentials.username())) {
            return;
        }

        chain.doFilter(request, response);

        if (isAuthenticatedAsOpdsUser()) {
            authRateLimitService.resetAlternateAuthAttempts(AUTH_PATH, ip);
            if (credentials.username() != null) {
                authRateLimitService.resetAlternateAuthAttemptsByCredential(AUTH_PATH, credentials.username());
            }
        } else if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
            authRateLimitService.recordFailedAlternateAuthAttempt(AUTH_PATH, ip);
            if (credentials.username() != null) {
                authRateLimitService.recordFailedAlternateAuthAttemptByCredential(AUTH_PATH, credentials.username());
            }
        }
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

    private BasicCredentials parseBasicCredentials(String authorization) {
        if (authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
            return new BasicCredentials(null);
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(authorization.substring(BASIC_PREFIX.length()).trim());
            String credentials = new String(decoded, StandardCharsets.UTF_8);
            int separator = credentials.indexOf(':');
            if (separator < 0) {
                return new BasicCredentials(null);
            }
            return new BasicCredentials(credentials.substring(0, separator));
        } catch (IllegalArgumentException e) {
            log.debug("Invalid OPDS Basic auth header", e);
            return new BasicCredentials(null);
        }
    }

    private boolean isAuthenticatedAsOpdsUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OpdsUserDetails;
    }

    private boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private record BasicCredentials(String username) {
    }
}
