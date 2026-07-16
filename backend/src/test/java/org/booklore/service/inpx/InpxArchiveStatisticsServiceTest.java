package org.booklore.service.inpx;

import org.booklore.repository.BookFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpxArchiveStatisticsServiceTest {

    @Mock
    private BookFileRepository bookFileRepository;

    private QueuedTaskExecutor executor;
    private InpxArchiveStatisticsService service;

    @BeforeEach
    void setUp() {
        executor = new QueuedTaskExecutor();
        service = new InpxArchiveStatisticsService(bookFileRepository, executor);
    }

    @Test
    void serializesDifferentLibrariesAndCoalescesDuplicateRequests() {
        List<Long> executionOrder = new ArrayList<>();
        when(bookFileRepository.findArchiveStatistics(1L)).thenAnswer(invocation -> {
            executionOrder.add(1L);
            return List.of();
        });
        when(bookFileRepository.findArchiveStatistics(2L)).thenAnswer(invocation -> {
            executionOrder.add(2L);
            return List.of();
        });

        CompletableFuture<?> first = service.load(1L);
        CompletableFuture<?> firstDuplicate = service.load(1L);
        CompletableFuture<?> second = service.load(2L);
        CompletableFuture<?> secondDuplicate = service.load(2L);

        assertThat(firstDuplicate).isSameAs(first);
        assertThat(secondDuplicate).isSameAs(second);
        assertThat(executor.size()).isEqualTo(2);

        executor.runNext();
        assertThat(first).isCompleted();
        assertThat(second).isNotCompleted();

        executor.runNext();
        assertThat(second).isCompleted();
        assertThat(executionOrder).containsExactly(1L, 2L);
        verify(bookFileRepository, times(1)).findArchiveStatistics(1L);
        verify(bookFileRepository, times(1)).findArchiveStatistics(2L);
    }

    @Test
    void servesCompletedResultFromCacheUntilInvalidated() {
        when(bookFileRepository.findArchiveStatistics(1L)).thenReturn(List.of());

        service.load(1L);
        executor.runNext();

        assertThat(service.load(1L)).isCompleted();
        assertThat(executor.size()).isZero();

        service.invalidate(1L);
        service.load(1L);
        assertThat(executor.size()).isOne();
        executor.runNext();

        verify(bookFileRepository, times(2)).findArchiveStatistics(1L);
    }

    @Test
    void invalidationDuringCalculationDoesNotPublishStaleResultToCache() {
        when(bookFileRepository.findArchiveStatistics(1L)).thenReturn(List.of());

        CompletableFuture<?> stale = service.load(1L);
        service.invalidate(1L);
        CompletableFuture<?> fresh = service.load(1L);

        assertThat(fresh).isNotSameAs(stale);
        assertThat(executor.size()).isEqualTo(2);

        executor.runNext();
        assertThat(stale).isCompleted();
        assertThat(fresh).isNotCompleted();
        assertThat(executor.size()).isOne();

        executor.runNext();
        assertThat(fresh).isCompleted();

        assertThat(service.load(1L)).isCompleted();
        assertThat(executor.size()).isZero();

        verify(bookFileRepository, times(2)).findArchiveStatistics(1L);
    }

    private static final class QueuedTaskExecutor implements TaskExecutor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }
    }
}
