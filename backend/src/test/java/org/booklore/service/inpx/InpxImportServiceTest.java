package org.booklore.service.inpx;

import org.booklore.config.security.service.LibraryAccessGuard;
import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.inpx.InpxBookReference;
import org.booklore.model.dto.inpx.InpxImportRequest;
import org.booklore.model.dto.inpx.InpxImportResult;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.repository.LibraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InpxImportServiceTest {

    private static final char SEPARATOR = 0x04;

    @TempDir
    Path tempDir;

    private LibraryRepository libraryRepository;
    private LibraryAccessGuard libraryAccessGuard;
    private InpxSourceResolver inpxSourceResolver;
    private InpxImportService service;

    @BeforeEach
    void setUp() {
        libraryRepository = mock(LibraryRepository.class);
        libraryAccessGuard = mock(LibraryAccessGuard.class);
        inpxSourceResolver = mock(InpxSourceResolver.class);
        service = new InpxImportService(new InpxParser(), libraryRepository, libraryAccessGuard, inpxSourceResolver);
    }

    @Test
    void importBooks_isNotTransactional() throws NoSuchMethodException {
        Method importBooks = InpxImportService.class.getMethod("importBooks", long.class, InpxImportRequest.class);

        assertThat(importBooks.getAnnotation(Transactional.class)).isNull();
        assertThat(InpxImportService.class.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void extractsOnlySelectedBookAndSkipsItOnRepeatedImport() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Path destination = Files.createDirectory(tempDir.resolve("destination"));
        Path index = createIndex(source);
        createBookArchive(source);

        givenDestinationLibrary(destination, LibrarySourceType.FILESYSTEM);
        givenResolvedSource(index, source);
        InpxImportRequest request = request();

        InpxImportResult first = service.importBooks(7L, request);
        InpxImportResult second = service.importBooks(7L, request);

        Path imported = destination.resolve("INPX/fb2-000001-000999/123.fb2");
        assertThat(first.getImported()).isEqualTo(1);
        assertThat(first.getFailed()).isZero();
        assertThat(second.getSkipped()).isEqualTo(1);
        assertThat(imported).hasContent("<FictionBook>selected</FictionBook>");
        assertThat(destination.resolve("INPX/fb2-000001-000999/124.fb2")).doesNotExist();
    }

    @Test
    void closesEachArchiveBeforeOpeningTheNextSelectedBook() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Path destination = Files.createDirectory(tempDir.resolve("destination"));
        Path index = createIndex(source,
                indexEntry("fb2-000001-000999.inp", "First book", "123"),
                indexEntry("fb2-001000-001999.inp", "Second book", "456"));
        createBookArchive(source, "fb2-000001-000999.zip", "123", "first");
        createBookArchive(source, "fb2-001000-001999.zip", "456", "second");

        givenDestinationLibrary(destination, LibrarySourceType.FILESYSTEM);
        givenResolvedSource(index, source);

        AtomicInteger openHandles = new AtomicInteger();
        AtomicInteger maxOpenHandles = new AtomicInteger();
        InpxImportService trackingService = spy(service);
        doAnswer(invocation -> new TrackingZipFile(invocation.getArgument(0), openHandles, maxOpenHandles))
                .when(trackingService)
                .openArchive(any(Path.class));

        InpxImportResult result = trackingService.importBooks(7L, request(
                reference("fb2-000001-000999.zip", "123"),
                reference("fb2-001000-001999.zip", "456")));

        assertThat(result.getImported()).isEqualTo(2);
        assertThat(result.getFailed()).isZero();
        assertThat(maxOpenHandles).hasValue(1);
        assertThat(openHandles).hasValue(0);
        assertThat(destination.resolve("INPX/fb2-000001-000999/123.fb2"))
                .hasContent("<FictionBook>first</FictionBook>");
        assertThat(destination.resolve("INPX/fb2-001000-001999/456.fb2"))
                .hasContent("<FictionBook>second</FictionBook>");
    }

    @Test
    void rejectsALibraryTheUserIsNotAssignedTo() throws IOException {
        Path destination = Files.createDirectory(tempDir.resolve("destination"));
        givenDestinationLibrary(destination, LibrarySourceType.FILESYSTEM);
        doThrow(ApiError.FORBIDDEN.createException("nope")).when(libraryAccessGuard).requireAccess(7L);

        InpxImportRequest request = request();
        assertThatThrownBy(() -> service.importBooks(7L, request))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("nope");
        verifyNoInteractions(inpxSourceResolver);
    }

    @Test
    void rejectsImportingIntoAnInpxLibraryBecauseItsRescanIgnoresLooseFiles() throws IOException {
        Path destination = Files.createDirectory(tempDir.resolve("destination"));
        givenDestinationLibrary(destination, LibrarySourceType.INPX);

        InpxImportRequest request = request();
        assertThatThrownBy(() -> service.importBooks(7L, request))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("select a filesystem library");
        verifyNoInteractions(inpxSourceResolver);
    }

    private void givenDestinationLibrary(Path destination, LibrarySourceType sourceType) {
        LibraryEntity library = LibraryEntity.builder()
                .id(7L)
                .sourceType(sourceType)
                .libraryPaths(new ArrayList<>())
                .build();
        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .id(9L)
                .library(library)
                .path(destination.toString())
                .build();
        library.getLibraryPaths().add(libraryPath);
        when(libraryRepository.findByIdWithPaths(7L)).thenReturn(Optional.of(library));
    }

    private void givenResolvedSource(Path index, Path source) {
        when(inpxSourceResolver.resolve(null, null, null))
                .thenReturn(new InpxSourceResolver.InpxSource(index.toString(), source.toString()));
    }

    private InpxImportRequest request() {
        return request(reference("fb2-000001-000999.zip", "123"));
    }

    private InpxImportRequest request(InpxBookReference... references) {
        InpxImportRequest request = new InpxImportRequest();
        request.setLibraryPathId(9L);
        request.setBooks(List.of(references));
        return request;
    }

    private Path createIndex(Path source) throws IOException {
        return createIndex(source, indexEntry("fb2-000001-000999.inp", "Нужная книга", "123"));
    }

    private Path createIndex(Path source, IndexEntry... indexEntries) throws IOException {
        Path index = source.resolve("library.inpx");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(index), StandardCharsets.UTF_8)) {
            output.putNextEntry(new ZipEntry("structure.info"));
            output.write("AUTHOR;GENRE;TITLE;SERIES;SERNO;FILE;SIZE;LIBID;DEL;EXT;DATE;LANG;LIBRATE;KEYWORDS"
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (IndexEntry entry : indexEntries) {
                output.putNextEntry(new ZipEntry(entry.inpName()));
                output.write(inpxRow(entry.title(), entry.fileName()).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return index;
    }

    private void createBookArchive(Path source) throws IOException {
        Path archive = source.resolve("fb2-000001-000999.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("123.fb2"));
            output.write("<FictionBook>selected</FictionBook>".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("124.fb2"));
            output.write("<FictionBook>not selected</FictionBook>".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private void createBookArchive(Path source, String archiveName, String fileName, String content) throws IOException {
        Path archive = source.resolve(archiveName);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(fileName + ".fb2"));
            output.write(("<FictionBook>" + content + "</FictionBook>").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private InpxBookReference reference(String archiveName, String fileName) {
        InpxBookReference reference = new InpxBookReference();
        reference.setArchiveName(archiveName);
        reference.setFileName(fileName);
        reference.setExtension("fb2");
        return reference;
    }

    private IndexEntry indexEntry(String inpName, String title, String fileName) {
        return new IndexEntry(inpName, title, fileName);
    }

    private String inpxRow(String title, String fileName) {
        return String.join(String.valueOf(SEPARATOR),
                "Иванов,Иван:", "fantasy", title, "", "", fileName, "42", fileName, "", "fb2",
                "2020", "ru", "", "");
    }

    private record IndexEntry(String inpName, String title, String fileName) {
    }

    private static class TrackingZipFile extends ZipFile {
        private final AtomicInteger openHandles;
        private boolean closed;

        TrackingZipFile(Path path, AtomicInteger openHandles, AtomicInteger maxOpenHandles) throws IOException {
            super(path.toFile());
            this.openHandles = openHandles;
            int currentOpen = openHandles.incrementAndGet();
            maxOpenHandles.updateAndGet(currentMax -> Math.max(currentMax, currentOpen));
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!closed) {
                    closed = true;
                    openHandles.decrementAndGet();
                }
            }
        }
    }
}
