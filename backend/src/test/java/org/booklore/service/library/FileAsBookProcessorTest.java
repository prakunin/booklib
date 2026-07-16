package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class FileAsBookProcessorTest {

    @Mock
    private BookGroupProcessor bookGroupProcessor;

    private FileAsBookProcessor fileAsBookProcessor;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        fileAsBookProcessor = new FileAsBookProcessor(bookGroupProcessor);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    @Test
    void processLibraryFiles_shouldProcessDifferentNamedFilesSeparately() {
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);
        LibraryPathEntity libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath("/library/path");

        LibraryFile file1 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book1.epub")
                .fileSubPath("books")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book2.pdf")
                .fileSubPath("books")
                .bookFileType(BookFileType.PDF)
                .build();

        fileAsBookProcessor.processLibraryFiles(List.of(file1, file2), libraryEntity);

        // Files with different base names are in separate groups
        verify(bookGroupProcessor, times(2)).process(any(), anyLong());
    }

    @Test
    void processLibraryFiles_shouldHandleEmptyList() {
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);

        fileAsBookProcessor.processLibraryFiles(new ArrayList<>(), libraryEntity);

        verify(bookGroupProcessor, never()).process(any(), anyLong());
    }

    @Test
    void processLibraryFiles_shouldNotGroupFilesInDifferentDirectories() {
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);
        LibraryPathEntity libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath("/library/path");

        LibraryFile file1 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book.epub")
                .fileSubPath("dir1")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book.pdf")
                .fileSubPath("dir2")
                .bookFileType(BookFileType.PDF)
                .build();

        fileAsBookProcessor.processLibraryFiles(List.of(file1, file2), libraryEntity);

        // Files in different directories should be processed separately
        verify(bookGroupProcessor, times(2)).process(any(), anyLong());
    }

    @Test
    void processLibraryFilesGrouped_shouldCallProcessForEachGroup() {
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);

        LibraryPathEntity libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath("/library/path");

        LibraryFile file1 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book1.epub")
                .fileSubPath("books")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book2.pdf")
                .fileSubPath("books")
                .bookFileType(BookFileType.PDF)
                .build();

        Map<String, List<LibraryFile>> groups = Map.of(
                "group1", List.of(file1),
                "group2", List.of(file2)
        );

        fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);

        verify(bookGroupProcessor, times(2)).process(any(), anyLong());
    }

    @Test
    void processLibraryFilesGrouped_shouldRetryTransientDbFailureThenSucceed() {
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);
        Map<String, List<LibraryFile>> groups = Map.of("group1", List.of(
                LibraryFile.builder().fileName("book1.epub").bookFileType(BookFileType.EPUB).build()));

        doThrow(new CannotCreateTransactionException("Hikari pool exhausted"))
                .doNothing()
                .when(bookGroupProcessor).process(any(), anyLong());

        FileAsBookProcessor.ProcessingOutcome outcome =
                fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);

        // First attempt fails transiently, retry succeeds
        verify(bookGroupProcessor, times(2)).process(any(), anyLong());
        assertThat(outcome.skippedGroups()).isEmpty();
    }

    @Test
    void processLibraryFilesGrouped_shouldRecordGroupSkippedWhenTransientFailurePersists() {
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);
        Map<String, List<LibraryFile>> groups = Map.of("group1", List.of(
                LibraryFile.builder().fileName("book1.epub").bookFileType(BookFileType.EPUB).build()));

        doThrow(new CannotCreateTransactionException("Hikari pool exhausted"))
                .when(bookGroupProcessor).process(any(), anyLong());

        FileAsBookProcessor.ProcessingOutcome outcome =
                fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);

        // Retries exhausted (MAX_ATTEMPTS), then the group is recorded as skipped, not lost
        verify(bookGroupProcessor, times(3)).process(any(), anyLong());
        assertThat(outcome.skippedGroups()).hasSize(1);
    }

    @Test
    void processLibraryFilesGrouped_shouldNotRetryNonTransientError() {
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);
        Map<String, List<LibraryFile>> groups = Map.of("group1", List.of(
                LibraryFile.builder().fileName("book1.epub").bookFileType(BookFileType.EPUB).build()));

        doThrow(new IllegalStateException("corrupt file"))
                .when(bookGroupProcessor).process(any(), anyLong());

        FileAsBookProcessor.ProcessingOutcome outcome =
                fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);

        // A real per-file error must not be retried, but is still recorded as skipped
        verify(bookGroupProcessor, times(1)).process(any(), anyLong());
        assertThat(outcome.skippedGroups()).hasSize(1);
    }

    @Test
    void processLibraryFilesGrouped_shouldContinueOtherGroupsAfterOneIsSkipped() {
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);
        Map<String, List<LibraryFile>> groups = Map.of(
                "good", List.of(LibraryFile.builder().fileName("ok.epub").bookFileType(BookFileType.EPUB).build()),
                "bad", List.of(LibraryFile.builder().fileName("bad.epub").bookFileType(BookFileType.EPUB).build()));

        doThrow(new IllegalStateException("corrupt file"))
                .when(bookGroupProcessor).process(argThatContainsFile("bad.epub"), anyLong());
        doNothing()
                .when(bookGroupProcessor).process(argThatContainsFile("ok.epub"), anyLong());

        FileAsBookProcessor.ProcessingOutcome outcome =
                fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);

        assertThat(outcome.skippedGroups()).hasSize(1);
    }

    @Test
    void processLibraryFilesGrouped_shouldStopRetryingWhenInterrupted() {
        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(1L);
        Map<String, List<LibraryFile>> groups = Map.of("group1", List.of(
                LibraryFile.builder().fileName("book1.epub").bookFileType(BookFileType.EPUB).build()));

        doThrow(new CannotCreateTransactionException("Hikari pool exhausted"))
                .when(bookGroupProcessor).process(any(), anyLong());

        Thread.currentThread().interrupt(); // make the backoff sleep abort immediately
        FileAsBookProcessor.ProcessingOutcome outcome =
                fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);

        // Interruption must abort retrying rather than busy-looping DB work with no backoff
        assertThat(Thread.interrupted()).isTrue(); // consume + assert the preserved interrupt flag
        verify(bookGroupProcessor, times(1)).process(any(), anyLong());
        assertThat(outcome.skippedGroups()).hasSize(1);
    }

    private static List<LibraryFile> argThatContainsFile(String fileName) {
        return org.mockito.ArgumentMatchers.argThat(files ->
                files != null && files.stream().anyMatch(f -> fileName.equals(f.getFileName())));
    }
}
