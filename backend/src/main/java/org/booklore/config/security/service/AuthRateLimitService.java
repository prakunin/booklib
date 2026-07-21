package org.booklore.config.security.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.enums.AuditAction;
import org.booklore.service.audit.AuditService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AuthRateLimitService {

    private static final int MAX_ATTEMPTS = 5;
    private static final String LOGIN_IP_KEY_PREFIX = "login:ip:";
    private static final String LOGIN_USER_KEY_PREFIX = "login:user:";
    private static final String REFRESH_KEY_PREFIX = "refresh:";

    private final Cache<String, AtomicInteger> attemptCache;
    private final AuditService auditService;

    public AuthRateLimitService(AuditService auditService) {
        this.auditService = auditService;
        this.attemptCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(15))
                .build();
    }

    // --- Login rate limiting ---

    public void checkLoginRateLimit(String ip) {
        checkRateLimit(LOGIN_IP_KEY_PREFIX + ip, AuditAction.LOGIN_RATE_LIMITED, "Login rate limited for IP: " + ip);
    }

    public void checkLoginRateLimitByUsername(String username) {
        String normalizedUsername = normalizeUsername(username);
        checkRateLimit(LOGIN_USER_KEY_PREFIX + normalizedUsername, AuditAction.LOGIN_RATE_LIMITED, "Login rate limited for username: " + normalizedUsername);
    }

    public void recordFailedLoginAttempt(String ip) {
        recordFailedAttempt(LOGIN_IP_KEY_PREFIX + ip);
    }

    public void recordFailedLoginAttemptByUsername(String username) {
        String normalizedUsername = normalizeUsername(username);
        recordFailedAttempt(LOGIN_USER_KEY_PREFIX + normalizedUsername);
    }

    public void resetLoginAttempts(String ip) {
        resetAttempts(LOGIN_IP_KEY_PREFIX + ip);
    }

    public void resetLoginAttemptsByUsername(String username) {
        String normalizedUsername = normalizeUsername(username);
        resetAttempts(LOGIN_USER_KEY_PREFIX + normalizedUsername);
    }

    // --- Refresh token rate limiting ---

    public void checkRefreshRateLimit(String ip) {
        checkRateLimit(REFRESH_KEY_PREFIX + ip, AuditAction.REFRESH_RATE_LIMITED, "Refresh rate limited for IP: " + ip);
    }

    public void recordFailedRefreshAttempt(String ip) {
        recordFailedAttempt(REFRESH_KEY_PREFIX + ip);
    }

    public void resetRefreshAttempts(String ip) {
        resetAttempts(REFRESH_KEY_PREFIX + ip);
    }

    // --- Alternate authentication rate limiting ---

    public void checkAlternateAuthRateLimit(String authPath, String ip) {
        checkRateLimit(alternateIpKey(authPath, ip), AuditAction.LOGIN_RATE_LIMITED,
                "Alternate auth rate limited for " + authPath + " IP: " + ip);
    }

    public void checkAlternateAuthRateLimitByCredential(String authPath, String credential) {
        String normalizedCredential = normalizeCredential(credential);
        checkRateLimit(alternateCredentialKey(authPath, normalizedCredential), AuditAction.LOGIN_RATE_LIMITED,
                "Alternate auth rate limited for " + authPath + " credential");
    }

    public void recordFailedAlternateAuthAttempt(String authPath, String ip) {
        recordFailedAttempt(alternateIpKey(authPath, ip));
    }

    public void recordFailedAlternateAuthAttemptByCredential(String authPath, String credential) {
        recordFailedAttempt(alternateCredentialKey(authPath, normalizeCredential(credential)));
    }

    public void resetAlternateAuthAttempts(String authPath, String ip) {
        resetAttempts(alternateIpKey(authPath, ip));
    }

    public void resetAlternateAuthAttemptsByCredential(String authPath, String credential) {
        resetAttempts(alternateCredentialKey(authPath, normalizeCredential(credential)));
    }

    // --- Shared internals ---

    private void checkRateLimit(String key, AuditAction action, String message) {
        AtomicInteger attempts = attemptCache.getIfPresent(key);
        if (attempts != null && attempts.get() >= MAX_ATTEMPTS) {
            auditService.log(action, message);
            throw ApiError.RATE_LIMITED.createException();
        }
    }

    private void recordFailedAttempt(String key) {
        attemptCache.get(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private void resetAttempts(String key) {
        attemptCache.invalidate(key);
    }

    private String normalizeUsername(String username) {
        return username != null ? username.trim().toLowerCase() : "";
    }

    private String normalizeCredential(String credential) {
        return credential != null ? credential.trim() : "";
    }

    private String alternateIpKey(String authPath, String ip) {
        return "alternate:" + authPath + ":ip:" + ip;
    }

    private String alternateCredentialKey(String authPath, String normalizedCredential) {
        return "alternate:" + authPath + ":credential:" + normalizedCredential;
    }
}
