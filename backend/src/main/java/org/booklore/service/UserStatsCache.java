package org.booklore.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Caches the per-user reading/listening analytics so a stats dashboard load — which fans out dozens
 * of GROUP BY aggregations over the reading_sessions table at once — does not recompute them on every
 * request.
 *
 * <p>The user id is always part of the key (supplied centrally by the caller), so one user can never
 * be served another user's stats. Value staleness from new sessions is bounded by a short write-expiry
 * and by {@link #invalidateUser(long)} on session recording, mirroring the TTL-bounded approach of the
 * catalog summary cache.
 */
@Component
public class UserStatsCache {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final long MAX_ENTRIES = 20_000;

    private final Cache<String, Object> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(MAX_ENTRIES)
            .build();

    @SuppressWarnings("unchecked")
    public <T> T get(long userId, String op, Supplier<T> loader) {
        return (T) cache.get(key(userId, op), ignored -> loader.get());
    }

    public void invalidateUser(long userId) {
        String prefix = userId + "|";
        cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String key(long userId, String op) {
        return userId + "|" + op;
    }
}
