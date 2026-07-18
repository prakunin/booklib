package org.booklore.app.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.booklore.app.dto.AppFilterOptions;
import org.booklore.service.event.BookAddedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Caches the per-scope filter options so the ~30 facet aggregate queries behind them are not
 * recomputed on every request. The loading {@code get} also coalesces concurrent misses: while one
 * request computes a key, other requests for the same key wait for its result instead of running
 * the whole aggregate set a second time in parallel.
 *
 * <p>As with {@link CatalogSummaryCache}, the key must encode everything that changes the result's
 * <em>visibility scope</em> — user id, admin flag, accessible libraries, and library/shelf scoping —
 * so a permission or assignment change immediately produces a different key rather than serving an
 * authorization-stale count. Content staleness from mutations is bounded by the write-expiry and by
 * {@link BookAddedEvent} invalidation on ingestion.
 */
@Component
public class FilterOptionsCache {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final long MAX_ENTRIES = 1_000;

    private final Cache<String, AppFilterOptions> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(MAX_ENTRIES)
            .build();

    public AppFilterOptions get(String key, Supplier<AppFilterOptions> loader) {
        return cache.get(key, ignored -> loader.get());
    }

    @EventListener
    public void onBookAdded(BookAddedEvent event) {
        cache.invalidateAll();
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
