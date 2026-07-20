package org.booklore;

import jakarta.persistence.EntityManager;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.mapper.BookMapper;
import org.booklore.repository.*;
import org.booklore.service.book.BookDownloadService;
import org.booklore.service.book.BookQueryService;
import org.booklore.service.book.BookService;
import org.booklore.service.book.BookUpdateService;
import org.booklore.service.progress.ReadingProgressService;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.service.FileStreamingService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.service.restriction.ContentRestrictionService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class BookServiceDeleteTests {

    private BookService bookService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        BookRepository bookRepository = mock(BookRepository.class);
        BookFileRepository bookFileRepository = mock(BookFileRepository.class);
        PdfViewerPreferencesRepository pdfViewerPreferencesRepository = mock(PdfViewerPreferencesRepository.class);
        EbookViewerPreferenceRepository ebookViewerPreferenceRepository = mock(EbookViewerPreferenceRepository.class);
        CbxViewerPreferencesRepository cbxViewerPreferencesRepository = mock(CbxViewerPreferencesRepository.class);
        NewPdfViewerPreferencesRepository newPdfViewerPreferencesRepository = mock(NewPdfViewerPreferencesRepository.class);
        FileService fileService = mock(FileService.class);
        BookMapper bookMapper = mock(BookMapper.class);
        UserBookProgressRepository userBookProgressRepository = mock(UserBookProgressRepository.class);
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        BookQueryService bookQueryService = mock(BookQueryService.class);
        ReadingProgressService readingProgressService = mock(ReadingProgressService.class);
        BookDownloadService bookDownloadService = mock(BookDownloadService.class);
        MonitoringRegistrationService monitoringRegistrationService = mock(MonitoringRegistrationService.class);
        BookUpdateService bookUpdateService = mock(BookUpdateService.class);
        SidecarMetadataWriter sidecarMetadataWriter = mock(SidecarMetadataWriter.class);
        FileStreamingService fileStreamingService = mock(FileStreamingService.class);
        AuditService auditService = mock(AuditService.class);
        ArchivedBookContentService archivedBookContentService = mock(ArchivedBookContentService.class);
        ContentRestrictionService contentRestrictionService = mock(ContentRestrictionService.class);
        EntityManager entityManager = mock(EntityManager.class);

        bookService = new BookService(
                bookRepository,
                bookFileRepository,
                entityManager,
                pdfViewerPreferencesRepository,
                cbxViewerPreferencesRepository,
                newPdfViewerPreferencesRepository,
                fileService,
                bookMapper,
                userBookProgressRepository,
                authenticationService,
                bookQueryService,
                readingProgressService,
                bookDownloadService,
                monitoringRegistrationService,
                bookUpdateService,
                ebookViewerPreferenceRepository,
                sidecarMetadataWriter,
                fileStreamingService,
                auditService,
                archivedBookContentService,
                contentRestrictionService
        );
    }

    @Test
    void deletesEmptyDirectoriesUpToLibraryRoot() throws IOException {
        Path libraryRoot = tempDir.resolve("libraryRoot");
        Files.createDirectories(libraryRoot);

        Path nestedDir1 = libraryRoot.resolve("1");
        Path nestedDir2 = nestedDir1.resolve("2");
        Path nestedDir3 = nestedDir2.resolve("3");
        Files.createDirectories(nestedDir3);

        bookService.deleteEmptyParentDirsUpToLibraryFolders(nestedDir3, Set.of(libraryRoot));

        assertThat(Files.exists(nestedDir3)).isFalse();
        assertThat(Files.exists(nestedDir2)).isFalse();
        assertThat(Files.exists(nestedDir1)).isFalse();
        assertThat(Files.exists(libraryRoot)).isTrue();
    }

    @Test
    void doesNotDeleteDirectoryWithImportantFile() throws IOException {
        Path libraryRoot = tempDir.resolve("libraryRoot");
        Files.createDirectories(libraryRoot);

        Path nestedDir = libraryRoot.resolve("nested");
        Files.createDirectories(nestedDir);

        Path importantFile = nestedDir.resolve("important.txt");
        Files.createFile(importantFile);

        bookService.deleteEmptyParentDirsUpToLibraryFolders(nestedDir, Set.of(libraryRoot));

        assertThat(Files.exists(nestedDir)).isTrue();
        assertThat(Files.exists(importantFile)).isTrue();
        assertThat(Files.exists(libraryRoot)).isTrue();
    }

    @Test
    void deletesIgnoredFilesBeforeDeletingDirectory() throws IOException {
        Path libraryRoot = tempDir.resolve("libraryRoot");
        Files.createDirectories(libraryRoot);

        Path nestedDir = libraryRoot.resolve("nested");
        Files.createDirectories(nestedDir);

        Path ignoredFile = nestedDir.resolve(".DS_Store");
        Files.createFile(ignoredFile);

        bookService.deleteEmptyParentDirsUpToLibraryFolders(nestedDir, Set.of(libraryRoot));

        assertThat(Files.exists(ignoredFile)).isFalse();
        assertThat(Files.exists(nestedDir)).isFalse();
        assertThat(Files.exists(libraryRoot)).isTrue();
    }

    @Test
    void stopsAtLibraryRoot() throws IOException {
        Path libraryRoot = tempDir.resolve("libraryRoot");
        Files.createDirectories(libraryRoot);

        bookService.deleteEmptyParentDirsUpToLibraryFolders(libraryRoot, Set.of(libraryRoot));

        assertThat(Files.exists(libraryRoot)).isTrue();
    }

    @Test
    void handlesUnreadableDirectoryGracefully() throws IOException {
        Path libraryRoot = tempDir.resolve("libraryRoot");
        Files.createDirectories(libraryRoot);

        Path nestedDir = libraryRoot.resolve("nested");
        Files.createDirectories(nestedDir);

        File nestedDirFile = nestedDir.toFile();

        boolean readableBefore = nestedDirFile.canRead();

        try {
            if (nestedDirFile.setReadable(false)) {
                assertThatCode(() -> bookService.deleteEmptyParentDirsUpToLibraryFolders(nestedDir, Set.of(libraryRoot)))
                        .doesNotThrowAnyException();
            } else {
                System.out.println("Could not change read permission; skipping unreadable directory test.");
            }
        } finally {
            nestedDirFile.setReadable(readableBefore);
        }
    }
}
