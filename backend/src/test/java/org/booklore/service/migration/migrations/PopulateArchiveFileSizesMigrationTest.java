package org.booklore.service.migration.migrations;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.migration.MigrationIncompleteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PopulateArchiveFileSizesMigrationTest {

    @Mock
    private BookFileRepository bookFileRepository;
    private final PlatformTransactionManager transactionManager = new NoOpTransactionManager();

    @TempDir
    private Path tempDir;

    @Test
    void populatesZipEntrySizeForArchivedBookFile() throws IOException {
        Path archivePath = tempDir.resolve("fb2-1.zip");
        writeZipEntry(archivePath, "837878.fb2", 2048);

        BookEntity book = book(tempDir, tempDir);
        BookFileEntity file = BookFileEntity.builder()
                .id(10L)
                .book(book)
                .fileName("837878.fb2")
                .fileSubPath("")
                .sourceArchive("fb2-1.zip")
                .sourceArchiveEntry("837878.fb2")
                .isBookFormat(true)
                .bookType(BookFileType.FB2)
                .build();

        when(bookFileRepository.findArchivedBookFilesMissingSizeAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(file));
        when(bookFileRepository.findArchivedBookFilesMissingSizeAfterId(eq(10L), any(Pageable.class)))
                .thenReturn(List.of());

        new PopulateArchiveFileSizesMigration(bookFileRepository, transactionManager).execute();

        ArgumentCaptor<List<BookFileEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookFileRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .satisfies(updated -> assertThat(updated.getFileSizeKb()).isEqualTo(2L));
    }

    @Test
    void prefersInpxArchivePathOverLibraryPath() throws IOException {
        Path libraryPath = tempDir.resolve("library");
        Path archiveRoot = tempDir.resolve("archives");
        writeZipEntry(archiveRoot.resolve("fb2-1.zip"), "837878.fb2", 3072);

        BookFileEntity file = BookFileEntity.builder()
                .id(10L)
                .book(book(libraryPath, archiveRoot))
                .fileName("837878.fb2")
                .fileSubPath("")
                .sourceArchive("fb2-1.zip")
                .sourceArchiveEntry("837878.fb2")
                .isBookFormat(true)
                .bookType(BookFileType.FB2)
                .build();

        when(bookFileRepository.findArchivedBookFilesMissingSizeAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(file));
        when(bookFileRepository.findArchivedBookFilesMissingSizeAfterId(eq(10L), any(Pageable.class)))
                .thenReturn(List.of());

        new PopulateArchiveFileSizesMigration(bookFileRepository, transactionManager).execute();

        assertThat(file.getFileSizeKb()).isEqualTo(3L);
    }

    @Test
    void leavesMigrationIncompleteWhenArchiveCannotBeResolved() {
        BookFileEntity file = BookFileEntity.builder()
                .id(10L)
                .book(book(tempDir.resolve("library"), tempDir.resolve("missing-archives")))
                .fileName("837878.fb2")
                .fileSubPath("")
                .sourceArchive("fb2-1.zip")
                .sourceArchiveEntry("837878.fb2")
                .isBookFormat(true)
                .bookType(BookFileType.FB2)
                .build();

        when(bookFileRepository.findArchivedBookFilesMissingSizeAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(file));
        when(bookFileRepository.findArchivedBookFilesMissingSizeAfterId(eq(10L), any(Pageable.class)))
                .thenReturn(List.of());

        PopulateArchiveFileSizesMigration migration = new PopulateArchiveFileSizesMigration(bookFileRepository, transactionManager);
        assertThatThrownBy(migration::execute)
                .isInstanceOf(MigrationIncompleteException.class)
                .hasMessageContaining("Unable to resolve file sizes for 1 archived book files");

        verify(bookFileRepository, never()).saveAll(any());
    }

    private BookEntity book(Path libraryPath, Path archiveRoot) {
        return BookEntity.builder()
                .library(LibraryEntity.builder().inpxArchivePath(archiveRoot.toString()).build())
                .libraryPath(LibraryPathEntity.builder().path(libraryPath.toString()).build())
                .build();
    }

    private void writeZipEntry(Path archivePath, String entryName, int bytes) throws IOException {
        Files.createDirectories(archivePath.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archivePath))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(new byte[bytes]);
            zip.closeEntry();
        }
    }

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op: test double, transactions are not actually managed
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op: test double, transactions are not actually managed
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op: test double, transactions are not actually managed
        }
    }
}
