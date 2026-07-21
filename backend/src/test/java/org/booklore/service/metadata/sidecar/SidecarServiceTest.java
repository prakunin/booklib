package org.booklore.service.metadata.sidecar;

import org.booklore.exception.APIException;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.sidecar.SidecarMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.enums.SidecarSyncStatus;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SidecarServiceTest {

    @Mock private BookRepository bookRepository;
    @Mock private LibraryRepository libraryRepository;
    @Mock private SidecarMetadataReader sidecarReader;
    @Mock private SidecarMetadataWriter sidecarWriter;
    @Mock private SidecarMetadataMapper sidecarMapper;
    @Mock private BookMetadataUpdater bookMetadataUpdater;

    @InjectMocks
    private SidecarService service;

    private static final Long BOOK_ID = 1L;
    private static final Long LIBRARY_ID = 2L;

    private BookEntity bookWithPath(Path path) {
        BookEntity book = new BookEntity() {
            @Override
            public Path getFullFilePath() {
                return path;
            }
        };
        book.setId(BOOK_ID);
        return book;
    }

    @Nested
    @DisplayName("getSidecarContent")
    class GetSidecarContentTests {

        @Test
        @DisplayName("returns the sidecar metadata when the book has a file path")
        void returnsSidecarMetadataWhenPathPresent() {
            Path path = Path.of("/library/book.epub");
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(bookWithPath(path)));
            SidecarMetadata metadata = new SidecarMetadata();
            when(sidecarReader.readSidecarMetadata(path)).thenReturn(Optional.of(metadata));

            Optional<SidecarMetadata> result = service.getSidecarContent(BOOK_ID);

            assertThat(result).contains(metadata);
        }

        @Test
        @DisplayName("returns empty without reading when the book has no file path")
        void returnsEmptyWhenNoPath() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(bookWithPath(null)));

            Optional<SidecarMetadata> result = service.getSidecarContent(BOOK_ID);

            assertThat(result).isEmpty();
            verify(sidecarReader, never()).readSidecarMetadata(any());
        }

        @Test
        @DisplayName("throws BOOK_NOT_FOUND when the book doesn't exist")
        void throwsWhenBookMissing() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSidecarContent(BOOK_ID)).isInstanceOf(APIException.class);
        }
    }

    @Nested
    @DisplayName("getSyncStatus")
    class GetSyncStatusTests {

        @Test
        @DisplayName("delegates to the reader for an existing book")
        void delegatesToReader() {
            BookEntity book = bookWithPath(Path.of("/library/book.epub"));
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(book));
            when(sidecarReader.getSyncStatus(book)).thenReturn(SidecarSyncStatus.IN_SYNC);

            SidecarSyncStatus result = service.getSyncStatus(BOOK_ID);

            assertThat(result).isEqualTo(SidecarSyncStatus.IN_SYNC);
        }

        @Test
        @DisplayName("throws BOOK_NOT_FOUND when the book doesn't exist")
        void throwsWhenBookMissing() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSyncStatus(BOOK_ID)).isInstanceOf(APIException.class);
        }
    }

    @Nested
    @DisplayName("exportToSidecar")
    class ExportToSidecarTests {

        @Test
        @DisplayName("writes the sidecar file for an existing book")
        void writesSidecarFile() {
            BookEntity book = bookWithPath(Path.of("/library/book.epub"));
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(book));

            service.exportToSidecar(BOOK_ID);

            verify(sidecarWriter).writeSidecarMetadata(book);
        }

        @Test
        @DisplayName("throws BOOK_NOT_FOUND when the book doesn't exist")
        void throwsWhenBookMissing() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.exportToSidecar(BOOK_ID)).isInstanceOf(APIException.class);
            verify(sidecarWriter, never()).writeSidecarMetadata(any());
        }
    }

    @Nested
    @DisplayName("importFromSidecar")
    class ImportFromSidecarTests {

        @Test
        @DisplayName("throws BOOK_NOT_FOUND when the book doesn't exist")
        void throwsWhenBookMissing() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.importFromSidecar(BOOK_ID)).isInstanceOf(APIException.class);
        }

        @Test
        @DisplayName("throws FILE_NOT_FOUND when the book has no file path")
        void throwsWhenNoPath() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(bookWithPath(null)));

            assertThatThrownBy(() -> service.importFromSidecar(BOOK_ID)).isInstanceOf(APIException.class);
        }

        @Test
        @DisplayName("throws FILE_NOT_FOUND when no sidecar file exists")
        void throwsWhenNoSidecarFile() {
            Path path = Path.of("/library/book.epub");
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(bookWithPath(path)));
            when(sidecarReader.readSidecarMetadata(path)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.importFromSidecar(BOOK_ID)).isInstanceOf(APIException.class);
        }

        @Test
        @DisplayName("applies the mapped metadata update when the sidecar maps to non-null metadata")
        void appliesMappedMetadata() {
            Path path = Path.of("/library/book.epub");
            BookEntity book = bookWithPath(path);
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(book));
            SidecarMetadata sidecar = new SidecarMetadata();
            when(sidecarReader.readSidecarMetadata(path)).thenReturn(Optional.of(sidecar));
            BookMetadata mapped = BookMetadata.builder().title("Imported Title").build();
            when(sidecarMapper.toBookMetadata(sidecar)).thenReturn(mapped);
            when(sidecarReader.readSidecarCover(path)).thenReturn(new byte[0]);

            service.importFromSidecar(BOOK_ID);

            ArgumentCaptor<MetadataUpdateContext> captor = ArgumentCaptor.forClass(MetadataUpdateContext.class);
            verify(bookMetadataUpdater).setBookMetadata(captor.capture());
            MetadataUpdateContext context = captor.getValue();
            assertThat(context.getBookEntity()).isSameAs(book);
            assertThat(context.getMetadataUpdateWrapper().getMetadata()).isSameAs(mapped);
            assertThat(context.getReplaceMode()).isEqualTo(MetadataReplaceMode.REPLACE_WHEN_PROVIDED);
            assertThat(context.isUpdateThumbnail()).isFalse();
        }

        @Test
        @DisplayName("skips the metadata update entirely when the sidecar maps to null metadata")
        void skipsUpdateWhenMappedMetadataNull() {
            Path path = Path.of("/library/book.epub");
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(bookWithPath(path)));
            SidecarMetadata sidecar = new SidecarMetadata();
            when(sidecarReader.readSidecarMetadata(path)).thenReturn(Optional.of(sidecar));
            when(sidecarMapper.toBookMetadata(sidecar)).thenReturn(null);
            when(sidecarReader.readSidecarCover(path)).thenReturn(new byte[0]);

            service.importFromSidecar(BOOK_ID);

            verify(bookMetadataUpdater, never()).setBookMetadata(any());
        }

        @Test
        @DisplayName("logs but does not fail when a sidecar cover is present (cover import is a separate operation)")
        void logsWhenCoverPresent() {
            Path path = Path.of("/library/book.epub");
            when(bookRepository.findByIdWithBookFiles(BOOK_ID)).thenReturn(Optional.of(bookWithPath(path)));
            SidecarMetadata sidecar = new SidecarMetadata();
            when(sidecarReader.readSidecarMetadata(path)).thenReturn(Optional.of(sidecar));
            when(sidecarMapper.toBookMetadata(sidecar)).thenReturn(null);
            when(sidecarReader.readSidecarCover(path)).thenReturn(new byte[]{1, 2, 3});

            assertThatCode(() -> service.importFromSidecar(BOOK_ID)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("bulkExport")
    class BulkExportTests {

        @Test
        @DisplayName("throws LIBRARY_NOT_FOUND when the library doesn't exist")
        void throwsWhenLibraryMissing() {
            when(libraryRepository.findById(LIBRARY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.bulkExport(LIBRARY_ID)).isInstanceOf(APIException.class);
        }

        @Test
        @DisplayName("exports every book and counts successes, continuing past a per-book failure")
        void exportsAllBooksAndCountsSuccesses() {
            LibraryEntity library = LibraryEntity.builder().id(LIBRARY_ID).name("My Library").build();
            when(libraryRepository.findById(LIBRARY_ID)).thenReturn(Optional.of(library));
            BookEntity book1 = new BookEntity();
            book1.setId(10L);
            BookEntity book2 = new BookEntity();
            book2.setId(11L);
            when(bookRepository.findAllByLibraryIdWithFiles(LIBRARY_ID)).thenReturn(List.of(book1, book2));
            doThrow(new RuntimeException("write failed")).when(sidecarWriter).writeSidecarMetadata(book1);

            int exported = service.bulkExport(LIBRARY_ID);

            assertThat(exported).isEqualTo(1);
            verify(sidecarWriter).writeSidecarMetadata(book2);
        }
    }

    @Nested
    @DisplayName("bulkImport")
    class BulkImportTests {

        @Test
        @DisplayName("throws LIBRARY_NOT_FOUND when the library doesn't exist")
        void throwsWhenLibraryMissing() {
            when(libraryRepository.findById(LIBRARY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.bulkImport(LIBRARY_ID)).isInstanceOf(APIException.class);
        }

        @Test
        @DisplayName("counts only the books that were successfully imported")
        void countsOnlySuccessfulImports() {
            LibraryEntity library = LibraryEntity.builder().id(LIBRARY_ID).name("My Library").build();
            when(libraryRepository.findById(LIBRARY_ID)).thenReturn(Optional.of(library));

            BookEntity noPathBook = bookWithPath(null);
            noPathBook.setId(20L);
            Path path = Path.of("/library/imported.epub");
            BookEntity importableBook = bookWithPath(path);
            importableBook.setId(21L);

            when(bookRepository.findAllByLibraryIdWithFiles(LIBRARY_ID)).thenReturn(List.of(noPathBook, importableBook));
            SidecarMetadata sidecar = new SidecarMetadata();
            when(sidecarReader.readSidecarMetadata(path)).thenReturn(Optional.of(sidecar));
            BookMetadata mapped = BookMetadata.builder().title("T").build();
            when(sidecarMapper.toBookMetadata(sidecar)).thenReturn(mapped);

            int imported = service.bulkImport(LIBRARY_ID);

            assertThat(imported).isEqualTo(1);
            verify(bookMetadataUpdater, times(1)).setBookMetadata(any());
        }
    }
}
