package org.booklore.app.service;

import org.booklore.service.event.BookAddedEvent;
import org.booklore.service.event.BookCatalogChangedEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ShellBookIdsCacheTest {

    private final ShellBookIdsCache cache = new ShellBookIdsCache();

    @Test
    void secondGetServesCachedValueWithoutReload() {
        AtomicInteger loads = new AtomicInteger();
        Set<Long> first = cache.get(() -> {
            loads.incrementAndGet();
            return Set.of(1L, 2L);
        });
        Set<Long> second = cache.get(() -> {
            loads.incrementAndGet();
            return Set.of(99L);
        });

        assertThat(first).isEqualTo(Set.of(1L, 2L));
        assertThat(second).isEqualTo(Set.of(1L, 2L));
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void bookAddedEventInvalidates() {
        cache.get(() -> Set.of(1L));

        cache.onBookAdded(new BookAddedEvent(null));

        assertThat(cache.get(Set::of)).isEmpty();
    }

    @Test
    void catalogChangedEventInvalidates() {
        cache.get(() -> Set.of(1L));

        cache.onCatalogChanged(new BookCatalogChangedEvent(null));

        assertThat(cache.get(Set::of)).isEmpty();
    }
}
