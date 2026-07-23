package org.booklore.app.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LibraryStatsRecomputeCoordinatorTest {

    @Test
    void recomputeLibraryDelegatesAndReturnsTrue() {
        AppBookService service = mock(AppBookService.class);
        LibraryStatsRecomputeCoordinator coordinator = new LibraryStatsRecomputeCoordinator(service);

        assertThat(coordinator.recomputeLibrary(7L)).isTrue();
        verify(service).recomputeLibraryStats(7L);
    }

    @Test
    void concurrentRecomputeOfSameLibraryIsCoalesced() throws Exception {
        AppBookService service = mock(AppBookService.class);
        CountDownLatch inRecompute = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        // First caller blocks inside the recompute while holding the per-library lock.
        doAnswerBlocking(service, inRecompute, release, calls);

        LibraryStatsRecomputeCoordinator coordinator = new LibraryStatsRecomputeCoordinator(service);

        Thread first = new Thread(() -> coordinator.recomputeLibrary(3L));
        first.start();
        assertThat(inRecompute.await(5, TimeUnit.SECONDS)).isTrue();

        // Second caller for the same library finds the lock held and coalesces (skips).
        boolean secondRan = coordinator.recomputeLibrary(3L);
        assertThat(secondRan).isFalse();

        release.countDown();
        first.join(5_000);
        assertThat(calls.get()).isEqualTo(1);
    }

    private void doAnswerBlocking(AppBookService service, CountDownLatch inRecompute,
                                  CountDownLatch release, AtomicInteger calls) {
        org.mockito.Mockito.doAnswer(invocation -> {
            calls.incrementAndGet();
            inRecompute.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(service).recomputeLibraryStats(3L);
    }
}
