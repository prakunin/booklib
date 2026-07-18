package org.booklore.service.watcher;

import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.library.LibraryProcessingService;
import org.booklore.service.library.LibraryScanListener;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LibraryFileEventProcessorQueueTest {

    @Test
    void enqueueEventCoalescesDuplicatePathsAndKinds() {
        LibraryFileEventProcessor processor = new LibraryFileEventProcessor(
                mock(LibraryRepository.class),
                mock(BookRepository.class),
                mock(BookFileTransactionalHandler.class),
                mock(BookFilePersistenceService.class),
                mock(LibraryProcessingService.class),
                mock(LibraryScanListener.class),
                mock(PendingDeletionPool.class));
        LibraryFileEventProcessor.FileEvent event = new LibraryFileEventProcessor.FileEvent(
                StandardWatchEventKinds.ENTRY_MODIFY, 7L, Path.of("/library/book.epub"), false);

        assertThat(processor.enqueueEvent(event)).isTrue();
        assertThat(processor.enqueueEvent(event)).isFalse();

        assertThat(processor.queuedEventCount()).isEqualTo(1);
        assertThat(LibraryFileEventProcessor.EVENT_QUEUE_CAPACITY).isEqualTo(10_000);
    }
}
