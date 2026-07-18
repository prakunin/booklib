package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthRateLimitService;
import org.booklore.config.security.userdetails.UserAuthenticationDetails;
import org.booklore.exception.APIException;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.repository.KoboUserSettingsRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.kobo.KoboTokenService;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
@FilterRegistration(enabled = false)
public class KoboAuthFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH = "kobo";

    private final KoboUserSettingsRepository koboUserSettingsRepository;
    private final UserRepository userRepository;
    private final BookLoreUserTransformer bookLoreUserTransformer;
    private final AuthRateLimitService authRateLimitService;
    private final KoboTokenService koboTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();

        String path = request.getRequestURI();
        String[] parts = path.split("/");
        if (parts.length < 4) {
            log.warn("KOBO token missing in path");
            if (isRateLimited(response, ip, null)) {
                return;
            }
            recordFailedAttempt(ip, null);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "KOBO token missing");
            return;
        }

        String token = resolveToken(request, parts);
        if (isRateLimited(response, ip, token)) {
            return;
        }

        var userTokenOpt = koboUserSettingsRepository.findByTokenHash(koboTokenService.hashToken(token));
        if (userTokenOpt.isEmpty() || koboTokenService.isExpired(userTokenOpt.get().getTokenExpiresAt())) {
            log.warn("Invalid KOBO token");
            recordFailedAttempt(ip, token);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid KOBO token");
            return;
        }

        var userToken = userTokenOpt.get();
        var userOpt = userRepository.findByIdWithDetails(userToken.getUserId());

        if (userOpt.isEmpty()) {
            log.warn("User not found for KOBO token");
            recordFailedAttempt(ip, token);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "User not found");
            return;
        }

        var entity = userOpt.get();
        if (entity.getPermissions() == null || !entity.getPermissions().isPermissionSyncKobo()) {
            log.warn("User {} does not have syncKobo permission", entity.getId());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient permissions");
            return;
        }

        BookLoreUser user = bookLoreUserTransformer.toDTO(entity);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_SYNC_KOBO"));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new UserAuthenticationDetails(request, user.getId()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        authRateLimitService.resetAlternateAuthAttempts(AUTH_PATH, ip);
        authRateLimitService.resetAlternateAuthAttemptsByCredential(AUTH_PATH, token);

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request, String[] pathParts) {
        String headerToken = request.getHeader(KoboTokenService.HEADER_NAME);
        if (headerToken != null && !headerToken.isBlank()) {
            return headerToken;
        }
        return pathParts[3];
    }

    private boolean isRateLimited(HttpServletResponse response, String ip, String token) throws IOException {
        try {
            authRateLimitService.checkAlternateAuthRateLimit(AUTH_PATH, ip);
            if (token != null) {
                authRateLimitService.checkAlternateAuthRateLimitByCredential(AUTH_PATH, token);
            }
            return false;
        } catch (APIException e) {
            response.sendError(e.getStatus().value(), e.getMessage());
            return true;
        }
    }

    private void recordFailedAttempt(String ip, String token) {
        authRateLimitService.recordFailedAlternateAuthAttempt(AUTH_PATH, ip);
        if (token != null) {
            authRateLimitService.recordFailedAlternateAuthAttemptByCredential(AUTH_PATH, token);
        }
    }
}
