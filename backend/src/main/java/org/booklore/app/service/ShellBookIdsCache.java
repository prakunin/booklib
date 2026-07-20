package org.booklore.app.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.booklore.service.event.BookAddedEvent;
import org.booklore.service.event.BookCatalogChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Caches the global set of "shell" book ids (books with no files that are not physical either)
 * used by {@link AppBookService} to exclude empty shells from facet aggregates. Unlike
 * {@link FilterOptionsCache} the value is visibility-independent — it feeds a predicate, not a
 * user-facing count — so a single global entry suffices and per-scope keys would only multiply
 * the underlying full-table scan. Staleness is bounded by the TTL and by ingestion and
 * catalog-change events.
 */
@Component
public class ShellBookIdsCache {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String KEY = "shell";

    private final Cache<String, Set<Long>> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(1)
            .build();

    public Set<Long> get(Supplier<Set<Long>> loader) {
        return cache.get(KEY, ignored -> loader.get());
    }

    @EventListener
    public void onBookAdded(BookAddedEvent event) {
        cache.invalidateAll();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCatalogChanged(BookCatalogChangedEvent event) {
        cache.invalidateAll();
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
