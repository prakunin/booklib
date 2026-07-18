package org.booklore.app.service;

import org.booklore.app.dto.AppFilterOptions;
import org.booklore.service.event.BookAddedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class FilterOptionsCacheTest {

    private final FilterOptionsCache cache = new FilterOptionsCache();

    private static AppFilterOptions options(List<AppFilterOptions.CountedOption> authors) {
        return AppFilterOptions.builder().authors(authors).build();
    }

    @Test
    void computesOncePerKeyAndServesCachedValueAfterwards() {
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppFilterOptions> loader = () -> {
            computations.incrementAndGet();
            return options(List.of(new AppFilterOptions.CountedOption("a", 1)));
        };

        AppFilterOptions first = cache.get("k1", loader);
        AppFilterOptions second = cache.get("k1", loader);

        assertThat(computations.get()).isEqualTo(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void computesSeparatelyPerKey() {
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppFilterOptions> loader = () -> {
            computations.incrementAndGet();
            return options(List.of());
        };

        cache.get("k1", loader);
        cache.get("k2", loader);

        assertThat(computations.get()).isEqualTo(2);
    }

    @Test
    void invalidateAllForcesRecomputeForEveryKey() {
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppFilterOptions> loader = () -> {
            computations.incrementAndGet();
            return options(List.of());
        };

        cache.get("k1", loader);
        cache.get("k2", loader);
        cache.invalidateAll();
        cache.get("k1", loader);
        cache.get("k2", loader);

        assertThat(computations.get()).isEqualTo(4);
    }

    @Test
    void bookAddedEventInvalidatesEveryEntry() {
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppFilterOptions> loader = () -> {
            computations.incrementAndGet();
            return options(List.of());
        };

        cache.get("k1", loader);
        cache.onBookAdded(new BookAddedEvent(null));
        cache.get("k1", loader);

        assertThat(computations.get()).isEqualTo(2);
    }
}
