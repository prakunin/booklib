package org.booklore.app.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.booklore.app.dto.AppCatalogSummary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Caches the per-user catalog summary so the expensive aggregations behind it (notably the
 * COUNT(DISTINCT author) over all visible books) are not recomputed on every request.
 *
 * <p>The key is supplied by the caller and must encode everything that changes the summary's
 * <em>visibility scope</em> — user id, admin flag, and the set of accessible libraries — so that a
 * permission or library-assignment change immediately produces a different key rather than serving
 * an authorization-stale count. Value staleness from content mutations (imports, etc.) is bounded by
 * a short write-expiry instead of event-based invalidation, since the set of mutations that could
 * change the counts is large; callers that know it changed can still {@link #invalidate(String)}.
 */
@Component
public class CatalogSummaryCache {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final long MAX_ENTRIES = 10_000;

    private final Cache<String, AppCatalogSummary> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(MAX_ENTRIES)
            .build();

    public AppCatalogSummary get(String key, Supplier<AppCatalogSummary> loader) {
        return cache.get(key, ignored -> loader.get());
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
