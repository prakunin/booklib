package org.booklore.service.enrichment.work;

import org.booklore.model.dto.smart.ResolvedWorkIdentity;
import org.booklore.model.entity.WorkIdentityEntity;
import org.booklore.repository.BookWorkLinkRepository;
import org.booklore.repository.WorkIdentityRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkIdentityServiceTest {

    private final WorkIdentityRepository workIdentityRepository = mock(WorkIdentityRepository.class);
    private final BookWorkLinkRepository bookWorkLinkRepository = mock(BookWorkLinkRepository.class);
    private final WorkIdentityService service =
            new WorkIdentityService(workIdentityRepository, bookWorkLinkRepository);

    private ResolvedWorkIdentity identity() {
        return new ResolvedWorkIdentity("The Master and Margarita", "Mikhail Bulgakov", "ru",
                null, null, null, 1967, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void returnsTheStoredIdentityWithoutResolving() {
        WorkIdentityEntity stored = WorkIdentityEntity.builder().id(1L).workKey("k").build();
        when(workIdentityRepository.findByWorkKey("k")).thenReturn(Optional.of(stored));
        AtomicInteger resolverCalls = new AtomicInteger();

        Optional<WorkIdentityEntity> result = service.findOrResolve("k", () -> {
            resolverCalls.incrementAndGet();
            return Optional.of(identity());
        });

        assertThat(result).contains(stored);
        assertThat(resolverCalls).hasValue(0);
    }

    @Test
    void returnsEmptyWithoutAKey() {
        assertThat(service.findOrResolve(null, () -> Optional.of(identity()))).isEmpty();
    }

    /**
     * The reason this class exists. A bulk run reaching ten copies of one book at once must not
     * start ten agent processes to compute the same answer.
     */
    @Test
    void resolvesOnceWhenManyThreadsAskForTheSameKeyAtOnce() throws Exception {
        int threads = 10;
        AtomicInteger resolverCalls = new AtomicInteger();
        WorkIdentityEntity stored = WorkIdentityEntity.builder().id(1L).workKey("k").build();

        // Empty until something stores it, then present — the repository as the resolver leaves it.
        when(workIdentityRepository.findByWorkKey("k"))
                .thenAnswer(invocation -> resolverCalls.get() == 0 ? Optional.empty() : Optional.of(stored));
        when(workIdentityRepository.save(any())).thenReturn(stored);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int thread = 0; thread < threads; thread++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    service.findOrResolve("k", () -> {
                        resolverCalls.incrementAndGet();
                        return Optional.of(identity());
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(resolverCalls).hasValue(1);
    }

    @Test
    void doesNotStoreAnythingWhenResolutionFails() {
        when(workIdentityRepository.findByWorkKey("k")).thenReturn(Optional.empty());

        assertThat(service.findOrResolve("k", Optional::empty)).isEmpty();
        verify(workIdentityRepository, never()).save(any());
    }
}
