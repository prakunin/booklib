package org.booklore.service.kobo;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class KoboTokenService {

    public static final String HEADER_NAME = "X-Booklore-Kobo-Token";
    private static final int TOKEN_BYTES = 32;
    private static final long TOKEN_TTL_DAYS = 90;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generateToken() {
        byte[] token = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public String hashToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Kobo token must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public Instant newExpiry() {
        return Instant.now().plus(TOKEN_TTL_DAYS, ChronoUnit.DAYS);
    }

    public boolean isExpired(Instant expiresAt) {
        return expiresAt == null || !expiresAt.isAfter(Instant.now());
    }
}
