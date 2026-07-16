package org.booklore.service.inpx;

import org.booklore.model.dto.inpx.InpxBookReference;
import org.booklore.model.dto.inpx.InpxImportRequest;
import org.booklore.model.dto.inpx.InpxImportResult;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.LibraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InpxImportServiceTest {

    private static final char SEPARATOR = 0x04;

    @TempDir
    Path tempDir;

    private LibraryRepository libraryRepository;
    private InpxImportService service;

    @BeforeEach
    void setUp() {
        libraryRepository = mock(LibraryRepository.class);
        service = new InpxImportService(new InpxParser(), libraryRepository);
    }

    @Test
    void extractsOnlySelectedBookAndSkipsItOnRepeatedImport() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Path destination = Files.createDirectory(tempDir.resolve("destination"));
        Path index = createIndex(source);
        createBookArchive(source);

        LibraryEntity library = LibraryEntity.builder().id(7L).libraryPaths(new ArrayList<>()).build();
        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .id(9L)
                .library(library)
                .path(destination.toString())
                .build();
        library.getLibraryPaths().add(libraryPath);
        when(libraryRepository.findById(7L)).thenReturn(Optional.of(library));

        InpxImportRequest request = request(index, source);

        InpxImportResult first = service.importBooks(request);
        InpxImportResult second = service.importBooks(request);

        Path imported = destination.resolve("INPX/fb2-000001-000999/123.fb2");
        assertThat(first.getImported()).isEqualTo(1);
        assertThat(first.getFailed()).isZero();
        assertThat(second.getSkipped()).isEqualTo(1);
        assertThat(imported).hasContent("<FictionBook>selected</FictionBook>");
        assertThat(destination.resolve("INPX/fb2-000001-000999/124.fb2")).doesNotExist();
    }

    private InpxImportRequest request(Path index, Path source) {
        InpxBookReference reference = new InpxBookReference();
        reference.setArchiveName("fb2-000001-000999.zip");
        reference.setFileName("123");
        reference.setExtension("fb2");

        InpxImportRequest request = new InpxImportRequest();
        request.setInpxPath(index.toString());
        request.setArchivePath(source.toString());
        request.setLibraryId(7L);
        request.setLibraryPathId(9L);
        request.setBooks(List.of(reference));
        return request;
    }

    private Path createIndex(Path source) throws IOException {
        String row = String.join(String.valueOf(SEPARATOR),
                "Иванов,Иван:", "fantasy", "Нужная книга", "", "", "123", "42", "123", "", "fb2",
                "2020", "ru", "", "");
        Path index = source.resolve("library.inpx");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(index), StandardCharsets.UTF_8)) {
            output.putNextEntry(new ZipEntry("structure.info"));
            output.write("AUTHOR;GENRE;TITLE;SERIES;SERNO;FILE;SIZE;LIBID;DEL;EXT;DATE;LANG;LIBRATE;KEYWORDS"
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("fb2-000001-000999.inp"));
            output.write(row.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
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
}
