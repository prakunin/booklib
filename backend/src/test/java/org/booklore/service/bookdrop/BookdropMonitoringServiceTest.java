package org.booklore.service.bookdrop;

import org.booklore.config.AppProperties;
import org.booklore.repository.BookdropFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookdropMonitoringServiceTest {

    private AppProperties appProperties;
    private BookdropEventHandlerService eventHandler;
    private BookdropFileRepository bookdropFileRepository;
    private BookdropMonitoringService monitoringService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        appProperties = mock(AppProperties.class);
        eventHandler = mock(BookdropEventHandlerService.class);
        bookdropFileRepository = mock(BookdropFileRepository.class);
        
        when(appProperties.getBookdropFolder()).thenReturn(tempDir.toString());
        monitoringService = new BookdropMonitoringService(appProperties, eventHandler, bookdropFileRepository);
    }

    @Test
    void scanExistingBookdropFiles_ShouldIgnoreDotUnderscoreFiles() throws IOException {
        Path validFile = tempDir.resolve("book.epub");
        Files.createFile(validFile);

        Path invalidFile = tempDir.resolve("._book.epub");
        Files.createFile(invalidFile);
        
        Path hiddenFile = tempDir.resolve(".hidden.epub");
        Files.createFile(hiddenFile);

        Path subDir = tempDir.resolve("subdir");
        Files.createDirectories(subDir);
        Path validFileInSubdir = subDir.resolve("another.epub");
        Files.createFile(validFileInSubdir);

        Path invalidFileInSubdir = subDir.resolve("._another.epub");
        Files.createFile(invalidFileInSubdir);

        when(bookdropFileRepository.findAllFilePathsIn(anyList())).thenReturn(List.of());

        monitoringService.start();
        
        monitoringService.stop();

        verify(eventHandler).enqueueFile(validFile, StandardWatchEventKinds.ENTRY_CREATE);
        verify(eventHandler).enqueueFile(validFileInSubdir, StandardWatchEventKinds.ENTRY_CREATE);

        verify(eventHandler, never()).enqueueFile(eq(invalidFile), any());
        verify(eventHandler, never()).enqueueFile(eq(hiddenFile), any());
        verify(eventHandler, never()).enqueueFile(eq(invalidFileInSubdir), any());
    }

    @Test
    void scanExistingBookdropFiles_ShouldSkipFilesAlreadyTrackedInDatabase() throws IOException {
        Path alreadyTracked = tempDir.resolve("already-tracked.epub");
        Files.createFile(alreadyTracked);

        Path newFile = tempDir.resolve("new-file.epub");
        Files.createFile(newFile);

        when(bookdropFileRepository.findAllFilePathsIn(anyList()))
                .thenReturn(List.of(alreadyTracked.toAbsolutePath().toString()));

        monitoringService.start();

        monitoringService.stop();

        verify(eventHandler, never()).enqueueFile(alreadyTracked, StandardWatchEventKinds.ENTRY_CREATE);
        verify(eventHandler).enqueueFile(newFile, StandardWatchEventKinds.ENTRY_CREATE);
    }

    private boolean readBooleanField(String fieldName) throws Exception {
        Field field = BookdropMonitoringService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (boolean) field.get(monitoringService);
    }

    @Nested
    @DisplayName("start() lifecycle")
    class StartLifecycle {

        @Test
        @DisplayName("creates the bookdrop folder when it does not yet exist")
        void start_createsMissingBookdropFolder() throws Exception {
            Path missingFolder = tempDir.resolve("nested/bookdrop");
            when(appProperties.getBookdropFolder()).thenReturn(missingFolder.toString());

            assertThat(Files.exists(missingFolder)).isFalse();

            monitoringService.start();
            try {
                assertThat(Files.isDirectory(missingFolder)).isTrue();
                assertThat(monitoringService.isRunning()).isTrue();
                assertThat(readBooleanField("disabled")).isFalse();
            } finally {
                monitoringService.stop();
            }

            assertThat(monitoringService.isRunning()).isFalse();
        }

        @Test
        @DisplayName("disables monitoring when the bookdrop path is not a directory")
        void start_disablesMonitoring_whenBookdropPathIsNotADirectory() throws IOException {
            Path notADirectory = tempDir.resolve("a-regular-file");
            Files.createFile(notADirectory);
            when(appProperties.getBookdropFolder()).thenReturn(notADirectory.toString());

            monitoringService.start();

            assertThat(monitoringService.isRunning()).isFalse();

            monitoringService.rescanBookdropFolder();
            verifyNoInteractions(bookdropFileRepository);
        }
    }

    @Nested
    @DisplayName("pause / resume monitoring")
    class PauseResumeMonitoring {

        @Test
        @DisplayName("pauseMonitoring and resumeMonitoring are no-ops while monitoring is disabled")
        void pauseAndResume_areNoOps_whenDisabled() throws Exception {
            Path notADirectory = tempDir.resolve("a-regular-file");
            Files.createFile(notADirectory);
            when(appProperties.getBookdropFolder()).thenReturn(notADirectory.toString());
            monitoringService.start();
            assertThat(readBooleanField("disabled")).isTrue();

            monitoringService.pauseMonitoring();
            assertThat(readBooleanField("paused")).isFalse();

            monitoringService.resumeMonitoring();
            assertThat(readBooleanField("paused")).isFalse();
        }

        @Test
        @DisplayName("pauseMonitoring flips paused on, and a second call is idempotent")
        void pauseMonitoring_flipsPausedState_andIsIdempotent() throws Exception {
            monitoringService.start();
            try {
                assertThat(readBooleanField("paused")).isFalse();

                monitoringService.pauseMonitoring();
                assertThat(readBooleanField("paused")).isTrue();

                monitoringService.pauseMonitoring();
                assertThat(readBooleanField("paused")).isTrue();
            } finally {
                monitoringService.stop();
            }
        }

        @Test
        @DisplayName("resumeMonitoring re-registers the watch and a second call is idempotent")
        void resumeMonitoring_reregistersWatch_andIsIdempotent() throws Exception {
            monitoringService.start();
            try {
                monitoringService.pauseMonitoring();
                assertThat(readBooleanField("paused")).isTrue();

                monitoringService.resumeMonitoring();
                assertThat(readBooleanField("paused")).isFalse();

                // Not paused anymore, so this call hits the "cannot resume" branch and is a no-op.
                monitoringService.resumeMonitoring();
                assertThat(readBooleanField("paused")).isFalse();
            } finally {
                monitoringService.stop();
            }
        }
    }
}
