package org.booklore.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UserStatsCacheTest {

    private UserStatsCache cache;

    @BeforeEach
    void setUp() {
        cache = new UserStatsCache();
    }

    @Test
    void computesOnceThenServesFromCacheForSameUserAndOp() {
        AtomicInteger calls = new AtomicInteger();

        String first = cache.get(1L, "heatmap:2026", () -> "v" + calls.incrementAndGet());
        String second = cache.get(1L, "heatmap:2026", () -> "v" + calls.incrementAndGet());

        assertThat(first).isEqualTo("v1");
        assertThat(second).isEqualTo("v1");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void keysAreScopedByUserAndOp() {
        AtomicInteger calls = new AtomicInteger();

        cache.get(1L, "heatmap:2026", () -> calls.incrementAndGet());
        cache.get(2L, "heatmap:2026", () -> calls.incrementAndGet()); // different user
        cache.get(1L, "heatmap:2025", () -> calls.incrementAndGet()); // different op

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void invalidateUserClearsOnlyThatUser() {
        AtomicInteger calls = new AtomicInteger();
        cache.get(1L, "op", () -> calls.incrementAndGet());
        cache.get(2L, "op", () -> calls.incrementAndGet());

        cache.invalidateUser(1L);

        cache.get(1L, "op", () -> calls.incrementAndGet()); // recomputed
        cache.get(2L, "op", () -> calls.incrementAndGet()); // still cached

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void invalidateUserDoesNotAffectAUserWhoseIdSharesADigitPrefix() {
        // Key encoding must not treat user 1 as a prefix of user 12.
        AtomicInteger calls = new AtomicInteger();
        cache.get(1L, "op", () -> calls.incrementAndGet());
        cache.get(12L, "op", () -> calls.incrementAndGet());

        cache.invalidateUser(1L);

        cache.get(12L, "op", () -> calls.incrementAndGet()); // must still be cached

        assertThat(calls.get()).isEqualTo(2);
    }
}
