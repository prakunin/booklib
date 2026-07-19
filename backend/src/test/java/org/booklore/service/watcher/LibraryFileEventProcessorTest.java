package org.booklore.service.watcher;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.library.LibraryProcessingService;
import org.booklore.service.library.LibraryScanListener;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

class LibraryFileEventProcessorTest {

    @Mock private LibraryRepository libraryRepository;
    @Mock private BookRepository bookRepository;
    @Mock private BookFileTransactionalHandler bookFileTransactionalHandler;
    @Mock private BookFilePersistenceService bookFilePersistenceService;
    @Mock private LibraryProcessingService libraryProcessingService;
    @Mock private LibraryScanListener libraryScanListener;
    @Mock private PendingDeletionPool pendingDeletionPool;

    private LibraryFileEventProcessor processor;

    private AutoCloseable mocks;

    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

    @TempDir
    Path tempDir;

    @BeforeEach
    @SuppressWarnings("java:S1874") // AUTO_DETECT is a deprecated but still-supported compat mode; no replacement exists
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        processor = new LibraryFileEventProcessor(
                libraryRepository, bookRepository, bookFileTransactionalHandler,
                bookFilePersistenceService, libraryProcessingService, libraryScanListener, pendingDeletionPool);

        libraryPath = new LibraryPathEntity();
        libraryPath.setId(1L);
        libraryPath.setPath(tempDir.toString());

        library = LibraryEntity.builder()
                .id(1L)
                .name("Test Library")
                .libraryPaths(List.of(libraryPath))
                .organizationMode(LibraryOrganizationMode.AUTO_DETECT)
                .build();

        when(libraryRepository.findByIdWithPaths(1L)).thenReturn(Optional.of(library));
        when(bookFilePersistenceService.findMatchingLibraryPath(eq(library), any(Path.class)))
                .thenReturn(tempDir.toString());
        when(bookFilePersistenceService.getLibraryPathEntityForFile(library, tempDir.toString()))
                .thenReturn(libraryPath);

        // Start the event processing thread (normally done by SmartLifecycle)
        processor.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        processor.stop();
        mocks.close();
    }

    @Nested
    class HasPendingEventsForPaths {

        @Test
        void returnsFalseWhenNothingPending() {
            assertThat(processor.hasPendingEventsForPaths(Set.of(tempDir))).isFalse();
        }

        @Test
        void delegatesToPendingDeletionPool() {
            when(pendingDeletionPool.hasPendingForPaths(any())).thenReturn(true);

            assertThat(processor.hasPendingEventsForPaths(Set.of(tempDir))).isTrue();
        }
    }

    @Nested
    class ProcessEventDebouncing {

        @Test
        void createCancelsExistingDelete() throws Exception {
            Path file = tempDir.resolve("test.epub");
            Files.writeString(file, "content");

            // Schedule a DELETE
            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, file, false);

            // Immediately follow with CREATE (simulates quick rename)
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            // The DELETE should have been cancelled. Wait out the debounce window, then confirm
            // the DELETE event was never processed.
            await().pollDelay(1000, TimeUnit.MILLISECONDS).atMost(1500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                    verify(bookFilePersistenceService, never()).findBookFileByLibraryPathSubPathAndFileName(anyLong(), anyString(), anyString()));
        }

        @Test
        void directoryCreateScheduledWithDelay() throws IOException {
            Path folder = tempDir.resolve("newFolder");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve("test.epub"), "content");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Should not process immediately due to folder debounce
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
        }

        @Test
        void fileInsidePendingFolderResetsFolderTimer() throws IOException {
            Path folder = tempDir.resolve("audioFolder");
            Files.createDirectory(folder);

            // Trigger folder create first
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Then file inside it
            Path file = folder.resolve("track01.m4b");
            Files.writeString(file, "audio content");
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            // The folder debounce should have been reset, not processed yet
            verify(bookFileTransactionalHandler, never()).handleNewFolderAudiobook(anyLong(), any());
        }

        @Test
        void nonBookFileEventsIgnored() throws Exception {
            Path textFile = tempDir.resolve("readme.txt");
            Files.writeString(textFile, "hello");

            // Queue a modify event for a non-book file
            processor.processEvent(StandardWatchEventKinds.ENTRY_MODIFY, 1L, textFile, false);

            // Wait for processing to have had a chance to run, then confirm it never handled the file
            await().pollDelay(200, TimeUnit.MILLISECONDS).atMost(700, TimeUnit.MILLISECONDS).untilAsserted(() ->
                    verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any()));
        }
    }

    @Nested
    class FolderCreateHandling {

        @Test
        void bookPerFileMode_processesFilesIndividually() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);

            Path folder = tempDir.resolve("books");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve("book1.epub"), "content1");
            Files.writeString(folder.resolve("book2.pdf"), "content2");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Folder debounce is 5s, then queue processing; poll until both files have been handled
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(bookFileTransactionalHandler, times(2)).handleNewBookFile(eq(1L), any()));
        }

        @Test
        void bookPerFileMode_processesMultiFileAudiobookFolderAsOneBook() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);

            Path folder = tempDir.resolve("Chaptered Audiobook");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve("Chaptered Audiobook - 1 - Chapter One.mp3"), "audio content 1");
            Files.writeString(folder.resolve("Chaptered Audiobook - 2 - Chapter Two.mp3"), "audio content 2");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Folder debounce is 5s, then queue processing; poll until the audiobook folder has been handled
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(bookFileTransactionalHandler).handleNewFolderAudiobook(1L, folder);
                verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
                verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
            });
        }

        @Test
        void bookPerFolderMode_processesMultiFileAudiobookFolderAsOneBook() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);

            Path folder = tempDir.resolve("Chaptered Audiobook");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve("Chaptered Audiobook - 1 - Chapter One.mp3"), "audio content 1");
            Files.writeString(folder.resolve("Chaptered Audiobook - 2 - Chapter Two.mp3"), "audio content 2");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Folder debounce is 5s, then queue processing; poll until the audiobook folder has been handled
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(bookFileTransactionalHandler).handleNewFolderAudiobook(1L, folder);
                verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
                verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
            });
        }

        @Test
        void bookPerFileMode_ignoresFolderWithIgnoreFile() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);

            Path folder = tempDir.resolve("ignored");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve(".ignore"), "");
            Files.writeString(folder.resolve("book.epub"), "content");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Wait out the folder debounce + processing window, then confirm it was never handled
            await().pollDelay(8000, TimeUnit.MILLISECONDS).atMost(8500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                    verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any()));
        }

        @Test
        void bookPerFileMode_skipsFilesUnderIgnoredSubdirectory() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);

            Path folder = tempDir.resolve("parent");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve("good.epub"), "content");

            Path ignoredSub = folder.resolve("skipped");
            Files.createDirectory(ignoredSub);
            Files.writeString(ignoredSub.resolve(".ignore"), "");
            Files.writeString(ignoredSub.resolve("hidden.epub"), "content");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Only good.epub should be processed, not hidden.epub
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(bookFileTransactionalHandler, times(1)).handleNewBookFile(eq(1L), any()));
        }

        @Test
        void bookPerFolderMode_emptyFolder_doesNothing() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);

            Path folder = tempDir.resolve("empty");
            Files.createDirectory(folder);

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Wait out the folder debounce + processing window, then confirm nothing was processed
            await().pollDelay(8000, TimeUnit.MILLISECONDS).atMost(8500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
                verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            });
        }

        @Test
        void pathOutsideLibrary_skipped() throws Exception {
            Path outsideFolder = Files.createTempDirectory("outside");
            try {
                Files.writeString(outsideFolder.resolve("book.epub"), "content");

                processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, outsideFolder, true);

                // Wait out the folder debounce + processing window, then confirm handleEvent skipped
                // it because the path is outside the library
                await().pollDelay(8000, TimeUnit.MILLISECONDS).atMost(8500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                        verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any()));
            } finally {
                try (var stream = Files.walk(outsideFolder)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException _) { /* best-effort cleanup */ }
                    });
                }
            }
        }
    }

    @Nested
    class FolderDeleteHandling {

        @Test
        void folderWithBooks_addsToPool() {
            BookEntity book = BookEntity.builder()
                    .id(10L)
                    .library(library)
                    .libraryPath(libraryPath)
                    .deleted(false)
                    .bookFiles(new ArrayList<>(List.of(BookFileEntity.builder()
                            .id(100L)
                            .fileName("test.epub")
                            .fileSubPath("books")
                            .currentHash("hash")
                            .isBookFormat(true)
                            .bookType(BookFileType.EPUB)
                            .build())))
                    .build();

            when(bookRepository.findBooksWithFilesUnderPath(eq(1L), anyString()))
                    .thenReturn(List.of(book));

            Path folder = tempDir.resolve("books");

            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, folder, true);

            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(pendingDeletionPool).addFolderDeletion(any(), eq(1L), eq(List.of(book)), any()));
        }

        @Test
        void folderWithNoBooks_doesNotAddToPool() {
            when(bookRepository.findBooksWithFilesUnderPath(eq(1L), anyString()))
                    .thenReturn(List.of());

            Path folder = tempDir.resolve("emptybooks");

            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, folder, true);

            // Wait out the processing window, then confirm nothing was added to the pool
            await().pollDelay(2000, TimeUnit.MILLISECONDS).atMost(2500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                    verify(pendingDeletionPool, never()).addFolderDeletion(any(), anyLong(), any(), any()));
        }
    }

    @Nested
    class FileDeleteHandling {

        @Test
        void bookFileFound_addsToPool() {
            BookEntity book = BookEntity.builder()
                    .id(10L).library(library).libraryPath(libraryPath).deleted(false)
                    .bookFiles(new ArrayList<>()).build();
            BookFileEntity bookFile = BookFileEntity.builder()
                    .id(100L).book(book).fileName("test.epub").fileSubPath("sub")
                    .currentHash("hash").isBookFormat(true).bookType(BookFileType.EPUB).build();

            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(eq(1L), anyString(), eq("test.epub")))
                    .thenReturn(Optional.of(bookFile));

            Path file = tempDir.resolve("sub").resolve("test.epub");

            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, file, false);

            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(pendingDeletionPool).addFileDeletion(any(), eq(1L), eq(bookFile), eq(book), any()));
        }

        @Test
        void bookFileNotFound_logsAndContinues() {
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(anyLong(), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            Path file = tempDir.resolve("sub").resolve("missing.epub");

            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, file, false);

            // Wait out the processing window, then confirm nothing was added to the pool
            await().pollDelay(2000, TimeUnit.MILLISECONDS).atMost(2500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                    verify(pendingDeletionPool, never()).addFileDeletion(any(), anyLong(), any(), any(), any()));
        }
    }

    @Nested
    class FileCreateHandling {

        @Test
        void zeroByteFile_skipped() throws Exception {
            Path file = tempDir.resolve("empty.epub");
            Files.createFile(file);

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            // Wait out the stability check + processing window, then confirm it was never handled
            await().pollDelay(4000, TimeUnit.MILLISECONDS).atMost(4500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                    verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any()));
        }

        @Test
        void nonBookFile_skipped() throws Exception {
            Path file = tempDir.resolve("readme.txt");
            Files.writeString(file, "hello");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            await().pollDelay(4000, TimeUnit.MILLISECONDS).atMost(4500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                    verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any()));
        }

        @Test
        void validBookFile_processedAfterStabilityCheck() throws Exception {
            Path file = tempDir.resolve("book.epub");
            Files.writeString(file, "book content");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            // Stability check needs file to be stable for STABILITY_CHECK_INTERVAL_MS (3s)
            // then event goes to queue and is processed by virtual thread; poll until handled
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(bookFileTransactionalHandler).handleNewBookFile(eq(1L), any()));
        }
    }

    @Nested
    class Shutdown {

        @Test
        void stopCompletesCleanly() {
            assertThatCode(() -> processor.stop()).doesNotThrowAnyException();

            // Reinitialize so tearDown's shutdown doesn't fail
            processor = new LibraryFileEventProcessor(
                    libraryRepository, bookRepository, bookFileTransactionalHandler,
                    bookFilePersistenceService, libraryProcessingService, libraryScanListener, pendingDeletionPool);
        }
    }
}
