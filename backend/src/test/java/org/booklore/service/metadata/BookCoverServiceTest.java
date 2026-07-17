package org.booklore.service.metadata;

import org.booklore.config.AppProperties;
import org.booklore.exception.APIException;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.CoverExtraction;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookCoverUpdateProjection;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.book.BookQueryService;
import org.booklore.service.fileprocessor.BookFileProcessor;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.metadata.writer.MetadataWriter;
import org.booklore.service.metadata.writer.MetadataWriterFactory;
import org.booklore.service.file.FileFingerprint;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.enums.PermissionType;
import org.booklore.model.websocket.LogNotification;
import org.booklore.model.websocket.Topic;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.io.IOException;

@ExtendWith(MockitoExtension.class)
class BookCoverServiceTest {

    @Mock private AppProperties appProperties;
    @Mock private BookRepository bookRepository;
    @Mock private NotificationService notificationService;
    @Mock private AppSettingService appSettingService;
    @Mock private FileService fileService;
    @Mock private BookFileProcessorRegistry processorRegistry;
    @Mock private BookQueryService bookQueryService;
    @Mock private CoverImageGenerator coverImageGenerator;
    @Mock private MetadataWriterFactory metadataWriterFactory;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private Executor taskExecutor;
    @Mock private AuthenticationService authenticationService;
    @Mock private AppSettings appSettings;

    @InjectMocks
    private BookCoverService service;

    @BeforeEach
    void setUp() {
        lenient().when(appProperties.isLocalStorage()).thenReturn(true);
        lenient().when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder().username("testuser").build());
    }

    private BookEntity buildBook(long id, boolean coverLocked) {
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .title("Test Book")
                .coverLocked(coverLocked)
                .build();
        return BookEntity.builder()
                .id(id)
                .metadata(metadata)
                .bookFiles(new ArrayList<>())
                .build();
    }

    private BookEntity buildBookWithAudiobookLock(long id, boolean audiobookCoverLocked) {
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .title("Test Audiobook")
                .audiobookCoverLocked(audiobookCoverLocked)
                .coverLocked(false)
                .build();
        return BookEntity.builder()
                .id(id)
                .metadata(metadata)
                .bookFiles(new ArrayList<>())
                .build();
    }

    @Nested
    class BookNotFound {

        @Test
        void generateCustomCoverThrowsWhenBookNotFound() {
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateCustomCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Book not found");
        }

        @Test
        void updateCoverFromFileThrowsWhenBookNotFound() {
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.empty());
            MultipartFile file = mock(MultipartFile.class);

            assertThatThrownBy(() -> service.updateCoverFromFile(1L, file))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void updateCoverFromUrlThrowsWhenBookNotFound() {
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateCoverFromUrl(1L, "http://example.com/cover.jpg"))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void regenerateCoverThrowsWhenBookNotFound() {
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.regenerateCover(1L))
                    .isInstanceOf(APIException.class);
        }
    }

    @Nested
    class CoverLockChecks {

        @Test
        void generateCustomCoverThrowsWhenCoverLocked() {
            BookEntity book = buildBook(1L, true);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.generateCustomCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("locked");
        }

        @Test
        void updateCoverFromFileThrowsWhenCoverLocked() {
            BookEntity book = buildBook(1L, true);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            MultipartFile file = mock(MultipartFile.class);

            assertThatThrownBy(() -> service.updateCoverFromFile(1L, file))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("locked");
        }

        @Test
        void updateCoverFromUrlThrowsWhenCoverLocked() {
            BookEntity book = buildBook(1L, true);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.updateCoverFromUrl(1L, "http://example.com"))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("locked");
        }

        @Test
        void regenerateCoverThrowsWhenCoverLocked() {
            BookEntity book = buildBook(1L, true);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.regenerateCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("locked");
        }
    }

    @Nested
    class LazyInpxCoverGeneration {

        @Mock private BookFileProcessor processor;

        private BookEntity buildArchivedInpxBook(BookFileEntity bookFile) {
            BookEntity book = BookEntity.builder()
                    .id(42L)
                    .library(LibraryEntity.builder().sourceType(LibrarySourceType.INPX).build())
                    .metadata(BookMetadataEntity.builder().coverLocked(false).build())
                    .bookFiles(new ArrayList<>(List.of(bookFile)))
                    .build();
            bookFile.setBook(book);
            return book;
        }

        private BookFileEntity buildArchivedFb2File() {
            return BookFileEntity.builder()
                    .isBookFormat(true)
                    .bookType(BookFileType.FB2)
                    .sourceArchive("catalog.zip")
                    .sourceArchiveEntry("book.fb2")
                    .build();
        }

        private static final byte[] COVER_BYTES = new byte[]{1, 2, 3};

        @Test
        void generatesOnlyMissingArchivedCover() {
            BookFileEntity bookFile = buildArchivedFb2File();
            BookEntity book = buildArchivedInpxBook(bookFile);
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, bookFile)).thenReturn(CoverExtraction.found(COVER_BYTES));
            when(bookRepository.markCoverFoundIfStillMissing(eq(42L), any(), any())).thenReturn(1);
            when(fileService.saveCoverImageFromBytes(42L, COVER_BYTES)).thenReturn(true);

            boolean generated = service.tryGenerateMissingInpxCover(42L);

            assertThat(generated).isTrue();
            assertThat(book.getBookCoverHash()).isNotBlank();
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNotNull();
            assertThat(book.getCoverProbedAt()).isNull();
            verify(bookRepository).markCoverFoundIfStillMissing(eq(42L), any(), any());
            verify(bookRepository).save(book);
        }

        /**
         * The claim must be taken before the image is written, so that a probe which loses the race
         * has not already overwritten the winner's cover.
         */
        @Test
        void winningTheClaimWritesTheImageOnlyAfterTheClaimSucceeds() {
            BookFileEntity bookFile = buildArchivedFb2File();
            BookEntity book = buildArchivedInpxBook(bookFile);
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, bookFile)).thenReturn(CoverExtraction.found(COVER_BYTES));
            when(bookRepository.markCoverFoundIfStillMissing(eq(42L), any(), any())).thenReturn(1);
            when(fileService.saveCoverImageFromBytes(42L, COVER_BYTES)).thenReturn(true);

            service.tryGenerateMissingInpxCover(42L);

            InOrder inOrder = inOrder(bookRepository, fileService);
            inOrder.verify(bookRepository).markCoverFoundIfStillMissing(eq(42L), any(), any());
            inOrder.verify(fileService).saveCoverImageFromBytes(42L, COVER_BYTES);
        }

        /**
         * The regression test for the overwrite defect. An admin uploads a custom cover while a lazy
         * probe is in flight; the probe loses the guarded UPDATE. The guard only ever protected the
         * database column - the old code had already written the archive's image over the uploaded
         * file by this point, leaving the upload's hash in the database and the archive's bytes on
         * disk. Losing the claim must now cost nothing but discarded bytes: no write at all.
         */
        @Test
        void losingTheCoverFoundRaceWritesNoFileAndDoesNotTouchConcurrentState() throws IOException {
            BookFileEntity bookFile = buildArchivedFb2File();
            BookEntity book = buildArchivedInpxBook(bookFile);
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, bookFile)).thenReturn(CoverExtraction.found(COVER_BYTES));
            when(bookRepository.markCoverFoundIfStillMissing(eq(42L), any(), any())).thenReturn(0);

            boolean generated = service.tryGenerateMissingInpxCover(42L);

            assertThat(generated).isTrue();
            assertThat(book.getBookCoverHash()).isNull();
            verify(fileService, never()).saveCoverImageFromBytes(anyLong(), any());
            verify(fileService, never()).saveCoverImages(any(), anyLong());
            verify(bookRepository, never()).save(any());
        }

        /**
         * A won claim whose image never reaches disk would otherwise leave the book advertising a
         * cover hash with nothing behind it, and - because the claim also clears coverProbedAt -
         * permanently eligible yet permanently broken. Releasing the claim puts it back where it
         * started: no cover, no marker, retryable.
         */
        @Test
        void failedWriteAfterAWonClaimReleasesTheClaimSoTheBookStaysEligible() {
            BookFileEntity bookFile = buildArchivedFb2File();
            BookEntity book = buildArchivedInpxBook(bookFile);
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, bookFile)).thenReturn(CoverExtraction.found(COVER_BYTES));
            when(bookRepository.markCoverFoundIfStillMissing(eq(42L), any(), any())).thenReturn(1);
            when(fileService.saveCoverImageFromBytes(42L, COVER_BYTES)).thenReturn(false);

            boolean generated = service.tryGenerateMissingInpxCover(42L);

            assertThat(generated).isFalse();
            ArgumentCaptor<String> claimedHash = ArgumentCaptor.forClass(String.class);
            verify(bookRepository).markCoverFoundIfStillMissing(eq(42L), claimedHash.capture(), any());
            // Guarded on the hash this call claimed, so a concurrent writer's cover is never cleared.
            verify(bookRepository).clearCoverHashIfStillClaimed(42L, claimedHash.getValue());
            assertThat(book.getBookCoverHash()).isNull();
            assertThat(book.getCoverProbedAt()).isNull();
            verify(bookRepository, never()).save(any());
        }

        /**
         * A processor that falls back to the write-in-place default has already written the image by
         * the time it answers, so claim-before-write is impossible and this path cannot promise it
         * won't clobber someone else's cover. It must decline rather than claim after the fact.
         */
        @Test
        void refusesToClaimWhenTheProcessorCannotExtractWithoutWriting() {
            BookFileEntity bookFile = buildArchivedFb2File();
            BookEntity book = buildArchivedInpxBook(bookFile);
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, bookFile)).thenReturn(CoverExtraction.writtenInPlace());

            boolean generated = service.tryGenerateMissingInpxCover(42L);

            assertThat(generated).isFalse();
            verify(bookRepository, never()).markCoverFoundIfStillMissing(anyLong(), any(), any());
            verify(bookRepository, never()).markCoverProbedIfStillMissing(anyLong(), any());
            verify(bookRepository, never()).save(any());
        }

        @Test
        void skipsFilesystemBook() {
            BookEntity book = BookEntity.builder()
                    .id(42L)
                    .library(LibraryEntity.builder().sourceType(LibrarySourceType.FILESYSTEM).build())
                    .metadata(BookMetadataEntity.builder().coverLocked(false).build())
                    .build();
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));

            boolean generated = service.tryGenerateMissingInpxCover(42L);

            assertThat(generated).isFalse();
            verifyNoInteractions(processorRegistry);
            verify(bookRepository, never()).save(any());
        }

        @Test
        void skipsBookThatAlreadyHasCover() {
            BookEntity book = BookEntity.builder()
                    .id(42L)
                    .bookCoverHash("existing")
                    .library(LibraryEntity.builder().sourceType(LibrarySourceType.INPX).build())
                    .metadata(BookMetadataEntity.builder().coverLocked(false).build())
                    .build();
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));

            assertThat(service.tryGenerateMissingInpxCover(42L)).isFalse();

            verifyNoInteractions(processorRegistry);
        }

        @Test
        void skipsBookAlreadyProbedWithoutOpeningTheArchive() {
            BookFileEntity bookFile = buildArchivedFb2File();
            BookEntity book = buildArchivedInpxBook(bookFile);
            book.setCoverProbedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));

            boolean generated = service.tryGenerateMissingInpxCover(42L);

            assertThat(generated).isFalse();
            verifyNoInteractions(processorRegistry);
            verify(bookRepository, never()).save(any());
        }

        @Test
        void completedProbeWithNoCoverAtomicallyPersistsTheMarker() {
            BookFileEntity bookFile = buildArchivedFb2File();
            BookEntity book = buildArchivedInpxBook(bookFile);
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, bookFile)).thenReturn(CoverExtraction.noCoverFound());
            when(bookRepository.markCoverProbedIfStillMissing(eq(42L), any())).thenReturn(1);

            boolean generated = service.tryGenerateMissingInpxCover(42L);

            assertThat(generated).isFalse();
            assertThat(book.getCoverProbedAt()).isNotNull();
            assertThat(book.getBookCoverHash()).isNull();
            verify(bookRepository).markCoverProbedIfStillMissing(eq(42L), any());
            // The marker write is a standalone atomic UPDATE, not a full-entity save - a full save
            // here would risk clobbering fields a concurrent writer just changed.
            verify(bookRepository, never()).save(any());
        }

        @Test
        void failedReadDoesNotSetTheMarkerSoTheBookStaysEligibleForRetry() {
            BookFileEntity bookFile = buildArchivedFb2File();
            BookEntity book = buildArchivedInpxBook(bookFile);
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, bookFile)).thenReturn(CoverExtraction.readFailed());

            boolean generated = service.tryGenerateMissingInpxCover(42L);

            assertThat(generated).isFalse();
            assertThat(book.getCoverProbedAt()).isNull();
            assertThat(book.getBookCoverHash()).isNull();
            verify(bookRepository, never()).save(any());
            verify(bookRepository, never()).markCoverProbedIfStillMissing(anyLong(), any());
            verify(bookRepository, never()).markCoverFoundIfStillMissing(anyLong(), any(), any());
        }

        /**
         * Mirrors the worse race called out in review: a concurrent archive refresh clears the
         * marker and regenerates a cover (setting bookCoverHash) after this call already read the
         * book but before it persists its own "no cover" conclusion. The atomic guard must refuse
         * to overwrite that newer state instead of reinstating an obsolete "no cover".
         */
        @Test
        void losingTheNoCoverRaceDoesNotReinstateAnObsoleteMarker() {
            BookFileEntity bookFile = buildArchivedFb2File();
            BookEntity book = buildArchivedInpxBook(bookFile);
            when(bookRepository.findByIdWithBookFiles(42L)).thenReturn(Optional.of(book));
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, bookFile)).thenReturn(CoverExtraction.noCoverFound());
            when(bookRepository.markCoverProbedIfStillMissing(eq(42L), any())).thenReturn(0);

            boolean generated = service.tryGenerateMissingInpxCover(42L);

            assertThat(generated).isFalse();
            assertThat(book.getCoverProbedAt()).isNull();
            verify(bookRepository, never()).save(any());
        }
    }

    /**
     * A book must never hold a {@code bookCoverHash} and a stale {@code coverProbedAt} at once: the
     * book demonstrably has a cover, so an earlier "this file has none" verdict is obsolete, and
     * leaving it behind permanently blocks the lazy INPX path if the hash is ever cleared without a
     * rescan.
     * <p>
     * Every path below gives a book a cover through {@code updateBookCoverMetadata}, which is where
     * the hash is stamped and - since these are the paths that used to forget - where the marker is
     * now cleared. Stamping the hash and dropping the marker is one operation; none of these can do
     * half of it.
     */
    @Nested
    class StampingACoverHashAlwaysClearsTheProbeMarker {

        private static final Instant STALE_MARKER = Instant.parse("2026-01-01T00:00:00Z");

        private BookEntity buildProbedBook(long id) {
            BookEntity book = buildBook(id, false);
            book.setCoverProbedAt(STALE_MARKER);
            return book;
        }

        @Test
        void generateCustomCover() {
            BookEntity book = buildProbedBook(1L);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(coverImageGenerator.generateCover(any(), any())).thenReturn(new byte[]{1});
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.generateCustomCover(1L);

            assertThat(book.getBookCoverHash()).isNotNull();
            assertThat(book.getCoverProbedAt()).isNull();
        }

        @Test
        void updateCoverFromFile() {
            BookEntity book = buildProbedBook(1L);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.updateCoverFromFile(1L, mock(MultipartFile.class));

            assertThat(book.getBookCoverHash()).isNotNull();
            assertThat(book.getCoverProbedAt()).isNull();
        }

        @Test
        void updateCoverFromUrl() {
            BookEntity book = buildProbedBook(1L);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.updateCoverFromUrl(1L, "https://example.com/cover.jpg");

            assertThat(book.getBookCoverHash()).isNotNull();
            assertThat(book.getCoverProbedAt()).isNull();
        }

        @Test
        void processBulkCoverUpdate() throws Exception {
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMaxFileUploadSizeInMb()).thenReturn(5);

            BookEntity book = buildProbedBook(1L);
            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(book));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
            when(file.getBytes()).thenReturn(new byte[]{1, 2, 3});

            runInlineExecutorAndTransaction();

            service.updateCoverFromFileForBooks(Set.of(1L), file);

            assertThat(book.getBookCoverHash()).isNotNull();
            assertThat(book.getCoverProbedAt()).isNull();
        }

        @Test
        void processBulkCustomCoverGeneration() {
            BookEntity book = buildProbedBook(1L);
            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L))).thenReturn(List.of(book));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(coverImageGenerator.generateCover(any(), any())).thenReturn(new byte[]{1});
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            runInlineExecutorAndTransaction();

            service.generateCustomCoversForBooks(Set.of(1L));

            assertThat(book.getBookCoverHash()).isNotNull();
            assertThat(book.getCoverProbedAt()).isNull();
        }

        private void runInlineExecutorAndTransaction() {
            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));
            when(transactionTemplate.execute(any())).thenAnswer(inv ->
                    inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        }
    }

    @Nested
    class AudiobookCoverLockChecks {

        @Test
        void updateAudiobookCoverFromFileThrowsWhenLocked() {
            BookEntity book = buildBookWithAudiobookLock(1L, true);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            MultipartFile file = mock(MultipartFile.class);

            assertThatThrownBy(() -> service.updateAudiobookCoverFromFile(1L, file))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("locked");
        }

        @Test
        void updateAudiobookCoverFromUrlThrowsWhenLocked() {
            BookEntity book = buildBookWithAudiobookLock(1L, true);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.updateAudiobookCoverFromUrl(1L, "http://example.com"))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("locked");
        }

        @Test
        void regenerateAudiobookCoverThrowsWhenLocked() {
            BookEntity book = buildBookWithAudiobookLock(1L, true);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.regenerateAudiobookCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("locked");
        }

        @Test
        void generateCustomAudiobookCoverThrowsWhenLocked() {
            BookEntity book = buildBookWithAudiobookLock(1L, true);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.generateCustomAudiobookCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("locked");
        }
    }

    @Nested
    class RegenerateCover {

        @Test
        void throwsWhenNoEbookFileFound() {
            BookEntity book = buildBook(1L, false);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.regenerateCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("no ebook file found");
        }

        private BookEntity buildBookWithEbookFile(BookFileEntity ebookFile) {
            BookEntity book = buildBook(1L, false);
            book.setBookFiles(List.of(ebookFile));
            return book;
        }

        private BookFileEntity fb2File() {
            return BookFileEntity.builder().bookType(BookFileType.FB2).isBookFormat(true).build();
        }

        /**
         * A proven clean miss is the only case that may be stated to the user as a fact about their
         * file.
         */
        @Test
        void noCoverFoundReportsThatTheFileHasNoEmbeddedCover() {
            BookFileEntity ebookFile = fb2File();
            BookEntity book = buildBookWithEbookFile(ebookFile);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, ebookFile)).thenReturn(CoverExtraction.noCoverFound());

            assertThatThrownBy(() -> service.regenerateCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("no embedded cover image found");
        }

        /**
         * The regression test for the misleading-message defect: a user whose NAS is offline used to
         * be told, definitively, that their file contains no cover. READ_FAILED also covers the
         * processors that cannot tell a read failure from a clean miss, so the message must claim
         * neither fact - and must not be confusable with the clean-miss wording.
         */
        @Test
        void readFailureReportsAnUnknownCauseNotAMissingCover() {
            BookFileEntity ebookFile = fb2File();
            BookEntity book = buildBookWithEbookFile(ebookFile);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, ebookFile)).thenReturn(CoverExtraction.readFailed());

            assertThatThrownBy(() -> service.regenerateCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("could not read a cover image")
                    .hasMessageContaining("it may have none")
                    .hasMessageContaining("unreadable or temporarily unavailable")
                    .hasMessageNotContaining("no embedded cover image found");
        }

        /**
         * Extracted bytes that cannot be written are neither a missing cover nor an unreadable file.
         */
        @Test
        void unwritableCoverImageReportsASaveFailure() {
            BookFileEntity ebookFile = fb2File();
            BookEntity book = buildBookWithEbookFile(ebookFile);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, ebookFile)).thenReturn(CoverExtraction.found(new byte[]{1, 2, 3}));
            when(fileService.saveCoverImageFromBytes(eq(1L), any())).thenReturn(false);

            assertThatThrownBy(() -> service.regenerateCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("could not be saved");
            verify(bookRepository, never()).save(any());
        }

        /**
         * Regeneration is an explicit overwrite request, so extracted bytes are written straight
         * through - no claim to win first.
         */
        @Test
        void extractedBytesAreWrittenAndTheCoverMetadataIsStamped() {
            BookFileEntity ebookFile = fb2File();
            BookEntity book = buildBookWithEbookFile(ebookFile);
            byte[] coverBytes = new byte[]{1, 2, 3};
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, ebookFile)).thenReturn(CoverExtraction.found(coverBytes));
            when(fileService.saveCoverImageFromBytes(1L, coverBytes)).thenReturn(true);

            service.regenerateCover(1L);

            verify(fileService).saveCoverImageFromBytes(1L, coverBytes);
            assertThat(book.getBookCoverHash()).isNotNull();
            verify(bookRepository).save(book);
        }

        @Test
        void successfulRegenerationUpdatesCoverMetadata() {
            BookEntity book = buildBook(1L, false);
            BookFileEntity ebookFile = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB)
                    .isBookFormat(true)
                    .build();
            book.setBookFiles(List.of(ebookFile));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(processor);
            when(processor.extractCover(book, ebookFile)).thenReturn(CoverExtraction.writtenInPlace());

            service.regenerateCover(1L);

            assertThat(book.getMetadata().getCoverUpdatedOn()).isNotNull();
            assertThat(book.getMetadataUpdatedAt()).isNotNull();
            assertThat(book.getBookCoverHash()).isNotNull();
            verify(bookRepository).save(book);
        }

        /**
         * A successful explicit regeneration must not leave contradictory durable state: the
         * archive just yielded a cover, so any earlier "no cover" probe marker is now stale and
         * must be cleared - otherwise the lazy path stays permanently blocked if the hash is later
         * cleared without a rescan.
         */
        @Test
        void successfulRegenerationClearsAStaleNoCoverMarker() {
            BookEntity book = buildBook(1L, false);
            book.setCoverProbedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
            BookFileEntity ebookFile = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB)
                    .isBookFormat(true)
                    .build();
            book.setBookFiles(List.of(ebookFile));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(processor);
            when(processor.extractCover(book, ebookFile)).thenReturn(CoverExtraction.writtenInPlace());

            service.regenerateCover(1L);

            assertThat(book.getCoverProbedAt()).isNull();
            verify(bookRepository).save(book);
        }

        @Test
        void explicitRegenerationIsNotBlockedByThePreviouslyProbedMarker() {
            // Only the lazy tryGenerateMissingInpxCover path honours coverProbedAt. An explicit,
            // user-triggered regeneration must always be allowed to open the archive again.
            BookEntity book = buildBook(1L, false);
            book.setCoverProbedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
            BookFileEntity ebookFile = BookFileEntity.builder()
                    .bookType(BookFileType.FB2)
                    .isBookFormat(true)
                    .build();
            book.setBookFiles(List.of(ebookFile));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.FB2)).thenReturn(processor);
            when(processor.extractCover(book, ebookFile)).thenReturn(CoverExtraction.writtenInPlace());

            service.regenerateCover(1L);

            verify(processor).extractCover(book, ebookFile);
            assertThat(book.getBookCoverHash()).isNotNull();
            assertThat(book.getCoverProbedAt()).isNull();
            verify(bookRepository).save(book);
        }
    }

    @Nested
    class RegenerateAudiobookCover {

        @Test
        void throwsWhenNoAudiobookFileFound() {
            BookEntity book = buildBookWithAudiobookLock(1L, false);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.regenerateAudiobookCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("no audiobook file found");
        }

        @Test
        void throwsWhenProcessorFailsToExtractCover() {
            BookEntity book = buildBookWithAudiobookLock(1L, false);
            BookFileEntity audiobookFile = BookFileEntity.builder()
                    .bookType(BookFileType.AUDIOBOOK)
                    .build();
            book.setBookFiles(List.of(audiobookFile));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.AUDIOBOOK)).thenReturn(processor);
            when(processor.generateAudiobookCover(book)).thenReturn(false);

            assertThatThrownBy(() -> service.regenerateAudiobookCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("no embedded cover image found");
        }
    }

    @Nested
    class FormatPrioritySelection {

        @Test
        void selectsEbookByFormatPrioritySkippingAudiobook() {
            BookEntity book = buildBook(1L, false);

            BookFileEntity audiobookFile = BookFileEntity.builder()
                    .bookType(BookFileType.AUDIOBOOK)
                    .isBookFormat(false)
                    .build();
            BookFileEntity pdfFile = BookFileEntity.builder()
                    .bookType(BookFileType.PDF)
                    .isBookFormat(true)
                    .build();
            BookFileEntity epubFile = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB)
                    .isBookFormat(true)
                    .build();
            book.setBookFiles(List.of(audiobookFile, pdfFile, epubFile));

            LibraryEntity library = LibraryEntity.builder()
                    .formatPriority(List.of(BookFileType.AUDIOBOOK, BookFileType.EPUB, BookFileType.PDF))
                    .build();
            book.setLibrary(library);

            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(processor);
            when(processor.extractCover(eq(book), any())).thenReturn(CoverExtraction.writtenInPlace());

            service.regenerateCover(1L);

            verify(processorRegistry).getProcessorOrThrow(BookFileType.EPUB);
        }

        @Test
        void fallsBackToFirstNonAudiobookFileWhenNoPriorityMatch() {
            BookEntity book = buildBook(1L, false);

            BookFileEntity audiobookFile = BookFileEntity.builder()
                    .bookType(BookFileType.AUDIOBOOK)
                    .build();
            BookFileEntity pdfFile = BookFileEntity.builder()
                    .bookType(BookFileType.PDF)
                    .isBookFormat(true)
                    .build();
            book.setBookFiles(List.of(audiobookFile, pdfFile));
            book.setLibrary(LibraryEntity.builder().formatPriority(null).build());

            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.PDF)).thenReturn(processor);
            when(processor.extractCover(eq(book), any())).thenReturn(CoverExtraction.writtenInPlace());

            service.regenerateCover(1L);

            verify(processorRegistry).getProcessorOrThrow(BookFileType.PDF);
        }
    }

    @Nested
    class GenerateCustomCover {

        @Test
        void generatesCoverWithTitleAndAuthor() {
            BookEntity book = buildBook(1L, false);
            AuthorEntity author = AuthorEntity.builder().name("Jane Doe").build();
            book.getMetadata().setAuthors(List.of(author));
            book.setBookFiles(new ArrayList<>());
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(coverImageGenerator.generateCover("Test Book", "Jane Doe")).thenReturn(new byte[]{1, 2, 3});
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.generateCustomCover(1L);

            verify(coverImageGenerator).generateCover("Test Book", "Jane Doe");
            verify(fileService).createThumbnailFromBytes(eq(1L), any());
            verify(bookRepository).save(book);
        }
    }

    @Nested
    class BulkCoverFromFile {

        @Test
        void filtersOutLockedBooksForBulkOperations() {
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMaxFileUploadSizeInMb()).thenReturn(5);

            BookEntity unlocked = buildBook(1L, false);
            BookEntity locked = buildBook(2L, true);

            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L, 2L)))
                    .thenReturn(List.of(unlocked, locked));

            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            try {
                when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})); // JPEG
                when(file.getBytes()).thenReturn(new byte[]{1, 2, 3});
            } catch (Exception _) {}

            service.updateCoverFromFileForBooks(Set.of(1L, 2L), file);

            verify(bookQueryService).findAllWithMetadataByIds(Set.of(1L, 2L));
        }
    }

    @Nested
    class FileValidation {

        @Test
        void rejectsEmptyFile() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(true);

            assertThatThrownBy(() -> service.updateCoverFromFileForBooks(Set.of(1L), file))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        void rejectsNonImageContentType() throws Exception {
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMaxFileUploadSizeInMb()).thenReturn(5);

            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

            assertThatThrownBy(() -> service.updateCoverFromFileForBooks(Set.of(1L), file))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("JPEG and PNG");
        }

        @Test
        void rejectsFileLargerThanLimit() {
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMaxFileUploadSizeInMb()).thenReturn(5);

            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(6L * 1024 * 1024);

            assertThatThrownBy(() -> service.updateCoverFromFileForBooks(Set.of(1L), file))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("exceeds");
        }

        @Test
        void acceptsJpegFile() {
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMaxFileUploadSizeInMb()).thenReturn(5);

            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            try {
                when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
                when(file.getBytes()).thenReturn(new byte[]{1, 2, 3});
            } catch (Exception _) {}

            when(bookQueryService.findAllWithMetadataByIds(any())).thenReturn(List.of());

            service.updateCoverFromFileForBooks(Set.of(1L), file);
        }

        @Test
        void acceptsPngFile() {
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMaxFileUploadSizeInMb()).thenReturn(5);

            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            try {
                when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}));
                when(file.getBytes()).thenReturn(new byte[]{1, 2, 3});
            } catch (Exception _) {}

            when(bookQueryService.findAllWithMetadataByIds(any())).thenReturn(List.of());

            service.updateCoverFromFileForBooks(Set.of(1L), file);
        }

        @Test
        void rejectsIOExceptionOnRead() throws Exception {
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMaxFileUploadSizeInMb()).thenReturn(5);

            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getInputStream()).thenThrow(new IOException("Test error"));

            assertThatThrownBy(() -> service.updateCoverFromFileForBooks(Set.of(1L), file))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Failed to read");
        }
    }

    @Nested
    class UpdateCoverFromUrl {

        @Test
        void successfullyUpdatesCoverFromUrl() {
            BookEntity book = buildBook(1L, false);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.updateCoverFromUrl(1L, "https://example.com/cover.jpg");

            verify(fileService).createThumbnailFromUrl(1L, "https://example.com/cover.jpg");
            verify(bookRepository).save(book);
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNotNull();
            assertThat(book.getBookCoverHash()).isNotNull();
        }
    }

    @Nested
    class UpdateCoverFromFileSuccess {

        @Test
        void successfullyUpdatesCoverFromFile() {
            BookEntity book = buildBook(1L, false);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());
            MultipartFile file = mock(MultipartFile.class);

            service.updateCoverFromFile(1L, file);

            verify(fileService).createThumbnailFromFile(1L, file);
            verify(bookRepository).save(book);
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNotNull();
        }
    }

    @Nested
    class UpdateAudiobookCoverFromFile {

        @Test
        void successfullyUpdatesCoverFromFile() {
            BookEntity book = buildBookWithAudiobookLock(1L, false);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());
            MultipartFile file = mock(MultipartFile.class);

            service.updateAudiobookCoverFromFile(1L, file);

            verify(fileService).createAudiobookThumbnailFromFile(1L, file);
            verify(bookRepository).save(book);
            assertThat(book.getMetadata().getAudiobookCoverUpdatedOn()).isNotNull();
            assertThat(book.getAudiobookCoverHash()).isNotNull();
        }

        @Test
        void throwsWhenBookNotFound() {
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.empty());
            MultipartFile file = mock(MultipartFile.class);

            assertThatThrownBy(() -> service.updateAudiobookCoverFromFile(1L, file))
                    .isInstanceOf(APIException.class);
        }
    }

    @Nested
    class UpdateAudiobookCoverFromUrl {

        @Test
        void successfullyUpdatesCoverFromUrl() {
            BookEntity book = buildBookWithAudiobookLock(1L, false);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.updateAudiobookCoverFromUrl(1L, "https://example.com/audiobook-cover.jpg");

            verify(fileService).createAudiobookThumbnailFromUrl(1L, "https://example.com/audiobook-cover.jpg");
            verify(bookRepository).save(book);
            assertThat(book.getMetadata().getAudiobookCoverUpdatedOn()).isNotNull();
            assertThat(book.getAudiobookCoverHash()).isNotNull();
        }

        @Test
        void throwsWhenBookNotFound() {
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateAudiobookCoverFromUrl(1L, "https://example.com"))
                    .isInstanceOf(APIException.class);
        }
    }

    @Nested
    class RegenerateAudiobookCoverSuccess {

        @Test
        void successfullyRegeneratesAudiobookCover() {
            BookEntity book = buildBookWithAudiobookLock(1L, false);
            BookFileEntity audiobookFile = BookFileEntity.builder()
                    .bookType(BookFileType.AUDIOBOOK)
                    .build();
            book.setBookFiles(List.of(audiobookFile));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.AUDIOBOOK)).thenReturn(processor);
            when(processor.generateAudiobookCover(book)).thenReturn(true);
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.regenerateAudiobookCover(1L);

            assertThat(book.getMetadata().getAudiobookCoverUpdatedOn()).isNotNull();
            assertThat(book.getAudiobookCoverHash()).isNotNull();
            verify(bookRepository).save(book);
        }
    }

    @Nested
    class GenerateCustomAudiobookCover {

        @Test
        void successfullyGeneratesCustomAudiobookCover() {
            BookEntity book = buildBookWithAudiobookLock(1L, false);
            AuthorEntity author = AuthorEntity.builder().name("Author Name").build();
            book.getMetadata().setAuthors(List.of(author));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(coverImageGenerator.generateSquareCover("Test Audiobook", "Author Name")).thenReturn(new byte[]{1, 2});
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.generateCustomAudiobookCover(1L);

            verify(coverImageGenerator).generateSquareCover("Test Audiobook", "Author Name");
            verify(fileService).createAudiobookThumbnailFromBytes(eq(1L), any());
            verify(bookRepository).save(book);
            assertThat(book.getMetadata().getAudiobookCoverUpdatedOn()).isNotNull();
        }

        @Test
        void throwsWhenBookNotFound() {
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateCustomAudiobookCover(1L))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void throwsWhenAudiobookCoverLocked() {
            BookEntity book = buildBookWithAudiobookLock(1L, true);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.generateCustomAudiobookCover(1L))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void handlesNullAuthors() {
            BookEntity book = buildBookWithAudiobookLock(1L, false);
            book.getMetadata().setAuthors(null);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(coverImageGenerator.generateSquareCover("Test Audiobook", null)).thenReturn(new byte[]{1});
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.generateCustomAudiobookCover(1L);

            verify(coverImageGenerator).generateSquareCover("Test Audiobook", null);
        }
    }

    @Nested
    class BulkRegenerateCoversForBooks {

        @Test
        void delegatesToAsyncExecutorWithUnlockedBooks() {
            BookEntity unlocked = buildBook(1L, false);
            unlocked.setBookFiles(List.of(BookFileEntity.builder().bookType(BookFileType.EPUB).isBookFormat(true).build()));
            unlocked.setLibrary(LibraryEntity.builder().build());
            BookEntity locked = buildBook(2L, true);

            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L, 2L)))
                    .thenReturn(List.of(unlocked, locked));

            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));

            service.regenerateCoversForBooks(Set.of(1L, 2L));

            verify(taskExecutor).execute(any(Runnable.class));
        }
    }

    @Nested
    class BulkGenerateCustomCoversForBooks {

        @Test
        void delegatesToAsyncExecutorWithUnlockedBooks() {
            BookEntity unlocked = buildBook(1L, false);
            BookEntity locked = buildBook(2L, true);

            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L, 2L)))
                    .thenReturn(List.of(unlocked, locked));

            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));

            service.generateCustomCoversForBooks(Set.of(1L, 2L));

            verify(taskExecutor).execute(any(Runnable.class));
        }
    }

    @Nested
    class BulkUpdateCoverFromFileForBooks {

        @Test
        void processesOnlyUnlockedBooks() throws Exception {
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMaxFileUploadSizeInMb()).thenReturn(5);

            BookEntity unlocked = buildBook(1L, false);
            BookEntity locked = buildBook(2L, true);

            when(bookQueryService.findAllWithMetadataByIds(Set.of(1L, 2L)))
                    .thenReturn(List.of(unlocked, locked));

            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}));
            when(file.getBytes()).thenReturn(new byte[]{1, 2, 3});

            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));

            service.updateCoverFromFileForBooks(Set.of(1L, 2L), file);

            verify(taskExecutor).execute(any(Runnable.class));
        }
    }

    @Nested
    class RegenerateCoversAll {

        @Test
        void regeneratesCoversForAllUnlockedBooks() {
            BookEntity book = buildBook(1L, false);
            BookFileEntity ebookFile = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB).isBookFormat(true).build();
            book.setBookFiles(List.of(ebookFile));
            book.setLibrary(LibraryEntity.builder().build());

            when(bookQueryService.getAllFullBookEntitiesWithFiles()).thenReturn(List.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(transactionTemplate.execute(any())).thenAnswer(inv -> {
                var callback = inv.getArgument(0, TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(processor);
            when(processor.generateCover(book)).thenReturn(true);
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));

            service.regenerateCovers(false);

            verify(bookRepository).save(book);
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNotNull();
        }

        @Test
        void skipsLockedBooks() {
            BookEntity locked = buildBook(1L, true);
            BookFileEntity ebookFile = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB).isBookFormat(true).build();
            locked.setBookFiles(List.of(ebookFile));
            locked.setLibrary(LibraryEntity.builder().build());

            when(bookQueryService.getAllFullBookEntitiesWithFiles()).thenReturn(List.of(locked));

            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));

            service.regenerateCovers(false);

            verify(bookRepository, never()).save(any());
        }

        @Test
        void missingOnlySkipsBooksWithExistingCover() {
            BookEntity withCover = buildBook(1L, false);
            withCover.setBookCoverHash("existingHash");
            BookFileEntity ebookFile1 = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB).isBookFormat(true).build();
            withCover.setBookFiles(List.of(ebookFile1));
            withCover.setLibrary(LibraryEntity.builder().build());

            BookEntity withoutCover = buildBook(2L, false);
            withoutCover.setBookCoverHash(null);
            BookFileEntity ebookFile2 = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB).isBookFormat(true).build();
            withoutCover.setBookFiles(List.of(ebookFile2));
            withoutCover.setLibrary(LibraryEntity.builder().build());

            when(bookQueryService.getAllFullBookEntitiesWithFiles()).thenReturn(List.of(withCover, withoutCover));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(transactionTemplate.execute(any())).thenAnswer(inv -> {
                var callback = inv.getArgument(0, TransactionCallback.class);
                return callback.doInTransaction(null);
            });
            when(bookRepository.findByIdWithBookFiles(2L)).thenReturn(Optional.of(withoutCover));
            when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(processor);
            when(processor.generateCover(withoutCover)).thenReturn(true);
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));

            service.regenerateCovers(true);

            // Now it is 1 call because the initial query is moved to bookQueryService (which is mocked)
            verify(transactionTemplate, times(1)).execute(any());
            verify(bookRepository).save(withoutCover);
            verify(bookRepository, never()).findById(1L);
        }

        @Test
        void skipsBooksWithNoPrimaryFile() {
            BookEntity book = buildBook(1L, false);
            book.setBookFiles(new ArrayList<>());

            when(bookQueryService.getAllFullBookEntitiesWithFiles()).thenReturn(List.of(book));

            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));

            service.regenerateCovers(false);

            // Fetching list is moved to bookQueryService
            verify(transactionTemplate, never()).execute(any());
            verify(bookRepository, never()).save(any());
        }

        @Test
        void skipsBooksWithNoMetadata() {
            BookEntity book = BookEntity.builder()
                    .id(1L)
                    .metadata(null)
                    .bookFiles(new ArrayList<>())
                    .build();

            when(bookQueryService.getAllFullBookEntitiesWithFiles()).thenReturn(List.of(book));

            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));

            service.regenerateCovers(false);

            verify(transactionTemplate, never()).execute(any());
            verify(bookRepository, never()).save(any());
        }
    }

    @Nested
    class LockStatusEdgeCases {
        @Test
        void unlockedMethodsHandleNullMetadata() throws Exception {
            BookEntity book = BookEntity.builder().id(1L).metadata(null).build();
            when(bookQueryService.findAllWithMetadataByIds(any())).thenReturn(List.of(book));

            // Test getUnlockedBookCoverInfos via updateCoverFromFileForBooks (async)
            doAnswer(inv -> {
                inv.<Runnable>getArgument(0).run();
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));
            
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L);
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMaxFileUploadSizeInMb()).thenReturn(10);
            
            service.updateCoverFromFileForBooks(Set.of(1L), file);
            
            verify(bookQueryService).findAllWithMetadataByIds(any());
            // Should not proceed to transaction if filtered out
            verify(transactionTemplate, never()).execute(any());
        }
    }

    @Nested
    class FindEbookFileEdgeCases {

        @Test
        void returnsNullWhenBookFilesIsNull() {
            BookEntity book = buildBook(1L, false);
            book.setBookFiles(null);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.regenerateCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("no ebook file found");
        }

        @Test
        void returnsNullWhenBookFilesIsEmpty() {
            BookEntity book = buildBook(1L, false);
            book.setBookFiles(new ArrayList<>());
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.regenerateCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("no ebook file found");
        }

        @Test
        void returnsNullWhenOnlyAudiobookFiles() {
            BookEntity book = buildBook(1L, false);
            BookFileEntity audiobookFile = BookFileEntity.builder()
                    .bookType(BookFileType.AUDIOBOOK).isBookFormat(false).build();
            book.setBookFiles(List.of(audiobookFile));
            book.setLibrary(LibraryEntity.builder().formatPriority(null).build());
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            assertThatThrownBy(() -> service.regenerateCover(1L))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("no ebook file found");
        }

        @Test
        void selectsFirstMatchingFormatFromPriority() {
            BookEntity book = buildBook(1L, false);
            BookFileEntity pdfFile = BookFileEntity.builder()
                    .bookType(BookFileType.PDF).isBookFormat(true).build();
            BookFileEntity epubFile = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB).isBookFormat(true).build();
            book.setBookFiles(List.of(pdfFile, epubFile));
            book.setLibrary(LibraryEntity.builder()
                    .formatPriority(List.of(BookFileType.EPUB, BookFileType.PDF)).build());
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(processor);
            when(processor.extractCover(eq(book), eq(epubFile))).thenReturn(CoverExtraction.writtenInPlace());

            service.regenerateCover(1L);

            verify(processorRegistry).getProcessorOrThrow(BookFileType.EPUB);
        }

        @Test
        void fallsBackWhenPriorityFormatNotAvailable() {
            BookEntity book = buildBook(1L, false);
            BookFileEntity pdfFile = BookFileEntity.builder()
                    .bookType(BookFileType.PDF).isBookFormat(true).build();
            book.setBookFiles(List.of(pdfFile));
            book.setLibrary(LibraryEntity.builder()
                    .formatPriority(List.of(BookFileType.EPUB)).build());
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.PDF)).thenReturn(processor);
            when(processor.extractCover(eq(book), eq(pdfFile))).thenReturn(CoverExtraction.writtenInPlace());

            service.regenerateCover(1L);

            verify(processorRegistry).getProcessorOrThrow(BookFileType.PDF);
        }

        @Test
        void handlesEmptyFormatPriorityList() {
            BookEntity book = buildBook(1L, false);
            BookFileEntity epubFile = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB).isBookFormat(true).build();
            book.setBookFiles(List.of(epubFile));
            book.setLibrary(LibraryEntity.builder().formatPriority(List.of()).build());
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookFileProcessor processor = mock(BookFileProcessor.class);
            when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(processor);
            when(processor.extractCover(eq(book), eq(epubFile))).thenReturn(CoverExtraction.writtenInPlace());

            service.regenerateCover(1L);

            verify(processorRegistry).getProcessorOrThrow(BookFileType.EPUB);
        }
    }

    @Nested
    class WriteCoverToBookFile {

        @Test
        void writesAndUpdatesHashWhenWriterExists() {
            BookEntity book = buildBook(1L, false);
            BookFileEntity primaryFile = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB).isBookFormat(true)
                    .fileName("test.epub").fileSubPath("sub")
                    .build();
            book.setBookFiles(List.of(primaryFile));
            book.setLibrary(LibraryEntity.builder().build());
            book.setLibraryPath(LibraryPathEntity.builder().path("/lib").build());

            MetadataPersistenceSettings persistSettings = mock(MetadataPersistenceSettings.class);
            when(appSettingService.getAppSettings()).thenReturn(appSettings);
            when(appSettings.getMetadataPersistenceSettings()).thenReturn(persistSettings);
            when(persistSettings.isConvertCbrCb7ToCbz()).thenReturn(false);

            MetadataWriter writer = mock(MetadataWriter.class);
            when(metadataWriterFactory.getWriter(BookFileType.EPUB)).thenReturn(Optional.of(writer));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            try (MockedStatic<FileFingerprint> fpMock = mockStatic(FileFingerprint.class)) {
                fpMock.when(() -> FileFingerprint.generateHash(any())).thenReturn("abc123");

                service.updateCoverFromUrl(1L, "https://example.com/cover.jpg");

                verify(metadataWriterFactory).getWriter(BookFileType.EPUB);
                assertThat(primaryFile.getCurrentHash()).isEqualTo("abc123");
            }
        }

        @Test
        void skipsWriteWhenNoPrimaryFile() {
            BookEntity book = buildBook(1L, false);
            book.setBookFiles(new ArrayList<>());
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.updateCoverFromUrl(1L, "https://example.com/cover.jpg");

            verify(metadataWriterFactory, never()).getWriter(any());
        }
    }

    @Nested
    class NotifyBookCoverUpdate {

        @Test
        void sendsNotificationWhenUpdatesExist() {
            BookEntity book = buildBook(1L, false);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

            BookCoverUpdateProjection projection = mock(BookCoverUpdateProjection.class);
            when(bookRepository.findCoverUpdateInfoByIds(List.of(1L))).thenReturn(List.of(projection));

            service.updateCoverFromUrl(1L, "https://example.com/cover.jpg");

            verify(notificationService).sendMessage(any(), eq(List.of(projection)));
        }

        @Test
        void doesNotSendNotificationWhenNoUpdates() {
            BookEntity book = buildBook(1L, false);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.updateCoverFromUrl(1L, "https://example.com/cover.jpg");

            verify(notificationService, never()).sendMessage(any(), anyList());
        }
    }

    @Nested
    class GetAuthorNames {

        @Test
        void returnsNullForEmptyAuthors() {
            BookEntity book = buildBook(1L, false);
            book.getMetadata().setAuthors(new ArrayList<>());
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(coverImageGenerator.generateCover("Test Book", null)).thenReturn(new byte[]{1});
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.generateCustomCover(1L);

            verify(coverImageGenerator).generateCover("Test Book", null);
        }

        @Test
        void joinsMultipleAuthorNames() {
            BookEntity book = buildBook(1L, false);
            List<AuthorEntity> authors = new ArrayList<>();
            authors.add(AuthorEntity.builder().name("Alice").build());
            authors.add(AuthorEntity.builder().name("Bob").build());
            book.getMetadata().setAuthors(authors);
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(coverImageGenerator.generateCover(eq("Test Book"), argThat(s -> s.contains("Alice") && s.contains("Bob"))))
                    .thenReturn(new byte[]{1});
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.generateCustomCover(1L);

            verify(coverImageGenerator).generateCover(eq("Test Book"), argThat(s -> s.contains("Alice") && s.contains("Bob")));
        }
    }

    @Nested
    class NetworkStorageGating {

        @Test
        void writeCoverToBookFile_networkStorage_skipsFileWrite() {
            when(appProperties.isLocalStorage()).thenReturn(false);

            BookEntity book = buildBook(1L, false);
            BookFileEntity bookFile = BookFileEntity.builder()
                    .bookType(BookFileType.EPUB)
                    .isBookFormat(true)
                    .build();
            book.setBookFiles(List.of(bookFile));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.updateCoverFromUrl(1L, "https://example.com/cover.jpg");

            verify(metadataWriterFactory, never()).getWriter(any());
            verify(bookRepository).save(book);
        }

        @Test
        void writeAudiobookCoverToFile_networkStorage_skipsFileWrite() {
            when(appProperties.isLocalStorage()).thenReturn(false);

            BookEntity book = buildBookWithAudiobookLock(1L, false);
            BookFileEntity audiobookFile = BookFileEntity.builder()
                    .bookType(BookFileType.AUDIOBOOK)
                    .build();
            book.setBookFiles(List.of(audiobookFile));
            when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
            when(bookRepository.findCoverUpdateInfoByIds(any())).thenReturn(List.of());

            service.updateAudiobookCoverFromUrl(1L, "https://example.com/audiobook-cover.jpg");

            verify(metadataWriterFactory, never()).getWriter(any());
            verify(bookRepository).save(book);
        }
    }
}
