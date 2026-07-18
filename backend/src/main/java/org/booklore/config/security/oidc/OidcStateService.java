package org.booklore.config.security.oidc;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import org.booklore.exception.ApiError;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

@Service
public class OidcStateService {

    private static final String CODE_CHALLENGE_METHOD = "S256";

    private final Cache<String, StoredOidcState> stateCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(500)
            .build();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public OidcAuthorizationState generateState(HttpServletRequest request) {
        String sessionId = request.getSession(true).getId();
        String state = randomBase64Url(32);
        String codeVerifier = randomBase64Url(32);
        String nonce = randomBase64Url(32);
        String codeChallenge = createCodeChallenge(codeVerifier);

        stateCache.put(state, new StoredOidcState(sessionId, codeVerifier, nonce));
        return new OidcAuthorizationState(state, nonce, codeChallenge, CODE_CHALLENGE_METHOD);
    }

    public OidcAuthorizationFlow validateAndConsume(String state, HttpServletRequest request) {
        if (state == null || state.isBlank()) {
            throw ApiError.OIDC_INVALID_STATE.createException();
        }
        StoredOidcState storedState = stateCache.getIfPresent(state);
        if (storedState == null) {
            throw ApiError.OIDC_INVALID_STATE.createException();
        }
        stateCache.invalidate(state);

        var session = request.getSession(false);
        if (session == null || !Objects.equals(storedState.sessionId(), session.getId())) {
            throw ApiError.OIDC_INVALID_STATE.createException();
        }

        return new OidcAuthorizationFlow(storedState.codeVerifier(), storedState.nonce());
    }

    private String randomBase64Url(int byteCount) {
        byte[] bytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String createCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public record OidcAuthorizationState(
            String state,
            String nonce,
            String codeChallenge,
            String codeChallengeMethod
    ) {}

    public record OidcAuthorizationFlow(String codeVerifier, String nonce) {}

    private record StoredOidcState(String sessionId, String codeVerifier, String nonce) {}
}
