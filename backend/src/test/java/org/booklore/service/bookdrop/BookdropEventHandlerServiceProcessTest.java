package org.booklore.service.bookdrop;

import org.booklore.model.BookDropFileEvent;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BookdropEventHandlerServiceProcessTest {

    private BookdropFileRepository bookdropFileRepository;
    private NotificationService notificationService;
    private BookdropNotificationService bookdropNotificationService;
    private AppSettingService appSettingService;
    private BookdropMetadataService bookdropMetadataService;
    private BookdropEventHandlerService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        bookdropFileRepository = mock(BookdropFileRepository.class);
        notificationService = mock(NotificationService.class);
        bookdropNotificationService = mock(BookdropNotificationService.class);
        appSettingService = mock(AppSettingService.class);
        bookdropMetadataService = mock(BookdropMetadataService.class);
        service = new BookdropEventHandlerService(
                bookdropFileRepository, notificationService, bookdropNotificationService,
                appSettingService, bookdropMetadataService);
    }

    @Nested
    @DisplayName("processFile on create/modify events")
    class CreateOrModify {

        @Test
        void fileDoesNotExist_isIgnoredWithoutDbAccess() {
            Path missing = tempDir.resolve("gone.epub");

            service.processFile(new BookDropFileEvent(missing, StandardWatchEventKinds.ENTRY_CREATE));

            verifyNoInteractions(bookdropFileRepository);
        }

        @Test
        void directory_isIgnoredWithoutDbAccess() throws IOException {
            Path dir = Files.createDirectory(tempDir.resolve("subfolder"));

            service.processFile(new BookDropFileEvent(dir, StandardWatchEventKinds.ENTRY_CREATE));

            verifyNoInteractions(bookdropFileRepository);
        }

        @Test
        void unsupportedExtension_isIgnoredWithoutDbAccess() throws IOException {
            Path file = Files.createFile(tempDir.resolve("notes.txt"));

            service.processFile(new BookDropFileEvent(file, StandardWatchEventKinds.ENTRY_CREATE));

            verifyNoInteractions(bookdropFileRepository);
        }

        @Test
        void alreadyTrackedFile_isSkipped() throws IOException {
            Path file = Files.createFile(tempDir.resolve("book.epub"));
            when(bookdropFileRepository.findByFilePath(file.toAbsolutePath().toString()))
                    .thenReturn(Optional.of(BookdropFileEntity.builder().id(1L).build()));

            service.processFile(new BookDropFileEvent(file, StandardWatchEventKinds.ENTRY_CREATE));

            verify(bookdropFileRepository, never()).save(any());
        }

        @Test
        @DisplayName("a stable new file is persisted and metadata is attached when downloads are enabled")
        void stableNewFile_isPersistedWithMetadataDownload() throws IOException {
            Path file = Files.createFile(tempDir.resolve("book.epub"));
            Files.writeString(file, "some epub content");
            when(bookdropFileRepository.findByFilePath(file.toAbsolutePath().toString())).thenReturn(Optional.empty());
            when(bookdropFileRepository.save(any(BookdropFileEntity.class))).thenAnswer(inv -> {
                BookdropFileEntity e = inv.getArgument(0);
                e.setId(42L);
                return e;
            });
            AppSettings settings = AppSettings.builder().metadataDownloadOnBookdrop(true).build();
            when(appSettingService.getAppSettings()).thenReturn(settings);

            service.processFile(new BookDropFileEvent(file, StandardWatchEventKinds.ENTRY_CREATE));

            verify(bookdropFileRepository).save(any(BookdropFileEntity.class));
            verify(bookdropMetadataService).attachInitialMetadata(42L);
            verify(bookdropMetadataService).attachFetchedMetadata(42L);
            verify(bookdropNotificationService).sendBookdropFileSummaryNotification();
            // No other files remain queued, so the "all finished" notification is sent.
            verify(notificationService, times(2)).sendMessageToPermissions(any(), any(), any());
        }

        @Test
        @DisplayName("metadata download disabled only attaches initial metadata")
        void stableNewFile_withMetadataDownloadDisabled_onlyAttachesInitialMetadata() throws IOException {
            Path file = Files.createFile(tempDir.resolve("book2.epub"));
            Files.writeString(file, "some epub content");
            when(bookdropFileRepository.findByFilePath(file.toAbsolutePath().toString())).thenReturn(Optional.empty());
            when(bookdropFileRepository.save(any(BookdropFileEntity.class))).thenAnswer(inv -> {
                BookdropFileEntity e = inv.getArgument(0);
                e.setId(43L);
                return e;
            });
            AppSettings settings = AppSettings.builder().metadataDownloadOnBookdrop(false).build();
            when(appSettingService.getAppSettings()).thenReturn(settings);

            service.processFile(new BookDropFileEvent(file, StandardWatchEventKinds.ENTRY_CREATE));

            verify(bookdropMetadataService).attachInitialMetadata(43L);
            verify(bookdropMetadataService, never()).attachFetchedMetadata(any());
        }
    }

    @Nested
    @DisplayName("processFile on delete events")
    class Delete {

        @Test
        void deletedFile_removesMatchingDbRecordsAndNotifies() {
            Path file = tempDir.resolve("deleted-book.epub");
            when(bookdropFileRepository.deleteAllByFilePathStartingWith(anyString())).thenReturn(2);

            service.processFile(new BookDropFileEvent(file, StandardWatchEventKinds.ENTRY_DELETE));

            verify(bookdropFileRepository).deleteAllByFilePathStartingWith(file.toAbsolutePath().toString());
            verify(bookdropNotificationService).sendBookdropFileSummaryNotification();
        }
    }
}
