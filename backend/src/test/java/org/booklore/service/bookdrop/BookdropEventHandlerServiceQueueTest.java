package org.booklore.service.bookdrop;

import org.booklore.repository.BookdropFileRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BookdropEventHandlerServiceQueueTest {

    @Test
    void enqueueFileCoalescesDuplicatePathsAndKinds() {
        BookdropEventHandlerService service = new BookdropEventHandlerService(
                mock(BookdropFileRepository.class),
                mock(NotificationService.class),
                mock(BookdropNotificationService.class),
                mock(AppSettingService.class),
                mock(BookdropMetadataService.class));
        Path file = Path.of("/bookdrop/book.epub");

        service.enqueueFile(file, StandardWatchEventKinds.ENTRY_CREATE);
        service.enqueueFile(file, StandardWatchEventKinds.ENTRY_CREATE);

        assertThat(service.queuedFileCount()).isEqualTo(1);
        assertThat(BookdropEventHandlerService.FILE_QUEUE_CAPACITY).isEqualTo(10_000);
    }
}
