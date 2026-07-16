package org.booklore.app.service;

import org.booklore.app.dto.AppCatalogSummary;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSummaryCacheTest {

    private final CatalogSummaryCache cache = new CatalogSummaryCache();

    private static AppCatalogSummary summary(long totalAuthors) {
        return new AppCatalogSummary(0, totalAuthors, 0, 0, Map.of());
    }

    @Test
    void computesOncePerUserAndServesCachedValueAfterwards() {
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppCatalogSummary> loader = () -> {
            computations.incrementAndGet();
            return summary(42);
        };

        AppCatalogSummary first = cache.get("u1", loader);
        AppCatalogSummary second = cache.get("u1", loader);

        assertThat(computations.get()).isEqualTo(1);
        assertThat(second.totalAuthors()).isEqualTo(42);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void computesSeparatelyPerKey() {
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppCatalogSummary> loader = () -> {
            computations.incrementAndGet();
            return summary(1);
        };

        cache.get("u1", loader);
        cache.get("u2", loader);

        assertThat(computations.get()).isEqualTo(2);
    }

    @Test
    void differentVisibilityScopeForSameUserIsANewKey() {
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppCatalogSummary> loader = () -> {
            computations.incrementAndGet();
            return summary(1);
        };

        // Same user id, different accessible-library scope encoded in the key -> not a cache hit,
        // so a permission change is never served an authorization-stale count.
        cache.get("7|false|1,2,3", loader);
        cache.get("7|false|1,2", loader);

        assertThat(computations.get()).isEqualTo(2);
    }

    @Test
    void invalidateForcesRecomputeForThatKey() {
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppCatalogSummary> loader = () -> {
            computations.incrementAndGet();
            return summary(1);
        };

        cache.get("u1", loader);
        cache.invalidate("u1");
        cache.get("u1", loader);

        assertThat(computations.get()).isEqualTo(2);
    }

    @Test
    void invalidateAllForcesRecomputeForEveryKey() {
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppCatalogSummary> loader = () -> {
            computations.incrementAndGet();
            return summary(1);
        };

        cache.get("u1", loader);
        cache.get("u2", loader);
        cache.invalidateAll();
        cache.get("u1", loader);
        cache.get("u2", loader);

        assertThat(computations.get()).isEqualTo(4);
    }
}
