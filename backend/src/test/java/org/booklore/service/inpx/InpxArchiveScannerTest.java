package org.booklore.service.inpx;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpxArchiveScannerTest {

    @Mock
    private BookFileRepository bookFileRepository;
    @Mock
    private Fb2MetadataExtractor fb2MetadataExtractor;

    private InpxArchiveScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new InpxArchiveScanner(bookFileRepository, fb2MetadataExtractor);
    }

    @Test
    void discoversOnlyArchivesWhoseFb2EntriesAreNotFullyPersisted(@TempDir Path root) throws IOException {
        createArchive(root.resolve("complete.zip"), "1.fb2", "2.fb2");
        createArchive(root.resolve("partial.zip"), "3.fb2", "4.fb2");
        createArchive(root.resolve("new.ZIP"), "5.fb2");
        Files.writeString(root.resolve("notes.txt"), "ignored");
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of(
                new Object[]{"complete.zip", 2L},
                new Object[]{"partial.zip", 1L}));

        InpxArchiveScanner.Discovery discovery = scanner.discover(7L, root.toString());

        assertThat(discovery.candidates())
                .extracting(InpxArchiveScanner.ArchiveCandidate::archiveName)
                .containsExactly("new.ZIP", "partial.zip");
        assertThat(discovery.totalEntries()).isEqualTo(2);
    }

    @Test
    void skipsPersistedEntriesBeforeExtractingFb2Metadata(@TempDir Path root) throws IOException {
        createArchive(root.resolve("partial.zip"), "known.fb2", "new.fb2");
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.<Object[]>of(
                new Object[]{"partial.zip", 1L}));
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), eq(Set.of("partial.zip")), any()))
                .thenReturn(List.<Object[]>of(new Object[]{"partial.zip", "known.fb2"}));

        InpxArchiveScanner.Discovery discovery = scanner.discover(7L, root.toString());
        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(discovery, books::add, () -> false);

        assertThat(discovery.totalEntries()).isEqualTo(1);
        assertThat(books).singleElement().satisfies(book -> assertThat(book.getFileName()).isEqualTo("new"));
        verify(fb2MetadataExtractor, times(1)).extractMetadata(any(), contains("new.fb2"));
    }

    @Test
    void readsEmbeddedMetadataAndFallsBackToTheEntryName(@TempDir Path root) throws IOException {
        createArchive(root.resolve("new.zip"), "42.fb2", "43.fb2");
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of());
        when(fb2MetadataExtractor.extractMetadata(any(), contains("42.fb2"))).thenReturn(BookMetadata.builder()
                .title("The title")
                .authors(List.of("Jane Doe"))
                .categories(Set.of("fantasy"))
                .seriesName("Saga")
                .seriesNumber(2F)
                .publishedDate(LocalDate.of(2026, Month.JULY, 15))
                .language("ru")
                .build());

        InpxArchiveScanner.Discovery discovery = scanner.discover(7L, root.toString());
        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(discovery, books::add, () -> false);

        assertThat(books).hasSize(2);
        assertThat(books.getFirst().getTitle()).isEqualTo("The title");
        assertThat(books.getFirst().getAuthors()).containsExactly("Jane Doe");
        assertThat(books.getFirst().getSeries()).isEqualTo("Saga");
        assertThat(books.getFirst().getDate()).isEqualTo("2026-07-15");
        assertThat(books.get(1).getTitle()).isEqualTo("43");
        assertThat(books).extracting(InpxBookDto::getArchiveName).containsOnly("new.zip");
    }

    @Test
    void populatesFileSizeFromTheUncompressedZipEntry(@TempDir Path root) throws IOException {
        byte[] content = new byte[2500];
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(root.resolve("books.zip")))) {
            output.putNextEntry(new ZipEntry("42.fb2"));
            output.write(content);
            output.closeEntry();
        }
        InpxBookDto book = InpxBookDto.builder()
                .archiveName("books.zip")
                .fileName("42")
                .extension("fb2")
                .build();

        scanner.populateFileSizes(List.of(book), root.toString());

        assertThat(book.getFileSizeKb()).isEqualTo(2L);
    }

    @Test
    void listArchivesPrunesCacheEntriesThatAreNoLongerPresent(@TempDir Path root) throws IOException {
        Path first = root.resolve("first.zip");
        Path second = root.resolve("second.zip");
        createArchive(first, "1.fb2");
        createArchive(second, "2.fb2");

        assertThat(scanner.listArchives(root.toString())).hasSize(2);
        assertThat(scanner.archiveFileCacheSize()).isEqualTo(2);

        Files.delete(second);

        assertThat(scanner.listArchives(root.toString()))
                .extracting(InpxArchiveScanner.ArchiveFile::archiveName)
                .containsExactly("first.zip");
        assertThat(scanner.archiveFileCacheSize()).isEqualTo(1);
    }

    private void createArchive(Path path, String... entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String entry : entries) {
                output.putNextEntry(new ZipEntry(entry));
                output.write("<FictionBook/>".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
