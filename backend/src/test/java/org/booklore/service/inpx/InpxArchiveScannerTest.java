package org.booklore.service.inpx;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream.UnicodeExtraFieldPolicy;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.ArchiveService;
import org.booklore.service.metadata.extractor.DocMetadataExtractor;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.task.TaskExecutor;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
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
    @Mock
    private MetadataExtractorFactory metadataExtractorFactory;
    @Mock
    private DocMetadataExtractor docMetadataExtractor;
    @Mock
    private ArchiveService archiveService;

    private InpxArchiveScanner scanner;

    @BeforeEach
    void setUp() {
        ArchiveEntryMetadataRecognizer recognizer = new ArchiveEntryMetadataRecognizer(
                metadataExtractorFactory, docMetadataExtractor, new InpxFilenameMetadataParser());
        scanner = new InpxArchiveScanner(bookFileRepository, fb2MetadataExtractor, recognizer,
                archiveService, Runnable::run);
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
        assertThat(books).extracting(InpxBookDto::getBookType).containsOnly(BookFileType.FB2);
    }

    @Test
    void discoversNonFb2EntriesWithTypeAndFilenameMetadata(@TempDir Path root) throws IOException {
        createArchive(root.resolve("usr.zip"),
                "Megan_Lindholm_Silver_Lady.pdf",
                "Mark_Semyonovich_Solonin_Den_M.doc",
                "_zhurnal_Radio_1972_10.djvu");
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of());

        InpxArchiveScanner.Discovery discovery = scanner.discover(7L, root.toString());
        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(discovery, books::add, () -> false);

        assertThat(books).hasSize(3);

        InpxBookDto pdf = byExtension(books, "pdf");
        assertThat(pdf.getBookType()).isEqualTo(BookFileType.PDF);
        assertThat(pdf.getFileName()).isEqualTo("Megan_Lindholm_Silver_Lady");
        assertThat(pdf.getTitle()).isEqualTo("Silver Lady");
        assertThat(pdf.getAuthors()).containsExactly("Megan Lindholm");

        InpxBookDto doc = byExtension(books, "doc");
        assertThat(doc.getBookType()).isEqualTo(BookFileType.DOC);
        assertThat(doc.getTitle()).isEqualTo("Den M");
        assertThat(doc.getAuthors()).containsExactly("Mark Semyonovich Solonin");

        InpxBookDto djvu = byExtension(books, "djvu");
        assertThat(djvu.getBookType()).isEqualTo(BookFileType.DJVU);
        assertThat(djvu.getTitle()).isEqualTo("zhurnal Radio 1972 10");
        assertThat(djvu.getAuthors()).isEmpty();
    }

    @Test
    void discoversLegacyCyrillicNamesWithoutReplacementCharacters(@TempDir Path root) throws IOException {
        ZipCharsetTestFixtures.write(root.resolve("dos.zip"), "IBM866", false,
                UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Пушкин_Евгений_Онегин.fb2", "<FictionBook/>"));
        ZipCharsetTestFixtures.write(root.resolve("windows.zip"), "windows-1251", false,
                UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Толстой_Лев_Война_и_мир.pdf", "pdf"));
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of());

        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(scanner.discover(7L, root.toString()), books::add, () -> false);

        assertThat(books).extracting(InpxBookDto::getFileName)
                .containsExactly("Пушкин_Евгений_Онегин", "Толстой_Лев_Война_и_мир");
        assertThat(books).extracting(InpxBookDto::getTitle)
                .containsExactly("Пушкин_Евгений_Онегин", "Война и мир");
        assertThat(books).noneMatch(book -> book.getFileName().contains("?"));
    }

    @Test
    void preservesTheOriginalExtensionCaseSoTheArchiveEntryRoundTrips(@TempDir Path root) throws IOException {
        // The stored extension is reused to rebuild the archive entry name for later reads; a ZIP
        // entry named ".PDF" must not be lowercased to ".pdf" or the entry lookup misses it.
        createArchive(root.resolve("usr.zip"), "SoftLayn_Simulink__Toolboxes.PDF");
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of());

        InpxArchiveScanner.Discovery discovery = scanner.discover(7L, root.toString());
        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(discovery, books::add, () -> false);

        InpxBookDto book = books.getFirst();
        assertThat(book.getExtension()).isEqualTo("PDF");
        assertThat(book.getFileName()).isEqualTo("SoftLayn_Simulink__Toolboxes");
        assertThat(book.getBookType()).isEqualTo(BookFileType.PDF);
    }

    @Test
    void recursivelyDiscoversNestedZipLeavesWithoutEmittingContainerCards(@TempDir Path root) throws IOException {
        byte[] first = zipBytes("same.fb2", "<FictionBook>first</FictionBook>");
        byte[] second = zipBytes("same.fb2", "<FictionBook>second</FictionBook>");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(root.resolve("outer.zip")))) {
            putEntry(output, "first.zip", first);
            putEntry(output, "second.zip", second);
            putEntry(output, "direct.pdf", "pdf".getBytes(StandardCharsets.UTF_8));
        }
        // Equal old container count versus new leaf count must still trigger reconciliation.
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.<Object[]>of(
                new Object[]{"outer.zip", 3L}));
        when(bookFileRepository.findArchivesWithActiveGenericContainerEntries(7L))
                .thenReturn(List.of("outer.zip"));

        InpxArchiveScanner.Discovery discovery = scanner.discover(7L, root.toString());
        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(discovery, books::add, () -> false);

        assertThat(discovery.candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.hasNestedContainers()).isTrue());
        assertThat(books).extracting(InpxBookDto::getExtension).containsExactly("fb2", "fb2", "pdf");
        assertThat(books).noneMatch(book -> "zip".equalsIgnoreCase(book.getExtension()));
        assertThat(books.stream().filter(book -> "fb2".equals(book.getExtension()))
                .map(InpxBookDto::getSourceArchiveEntry)).doesNotHaveDuplicates();
    }

    @Test
    void resolvesEachLevelOfAMixedLegacyNestedZipIndependently(@TempDir Path root) throws IOException {
        byte[] nested = ZipCharsetTestFixtures.bytes("windows-1251", false,
                UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Толстой_Война_и_мир.fb2", "<FictionBook/>"));
        ZipCharsetTestFixtures.write(root.resolve("outer.zip"), "IBM866", false,
                UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Внутри.zip", nested));
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of());

        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(scanner.discover(7L, root.toString()), books::add, () -> false);

        assertThat(books).singleElement().satisfies(book -> {
            assertThat(book.getFileName()).isEqualTo("Толстой_Война_и_мир");
            assertThat(NestedArchiveLocator.decode(book.getSourceArchiveEntry()))
                    .containsExactly("Внутри.zip", "Толстой_Война_и_мир.fb2");
        });
    }

    @Test
    void skipsAnUnchangedNestedArchiveAfterLegacyContainersAreReconciled(@TempDir Path root) throws IOException {
        byte[] nested = zipBytes("book.fb2", "<FictionBook/>");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(root.resolve("outer.zip")))) {
            putEntry(output, "inner.zip", nested);
        }
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.<Object[]>of(
                new Object[]{"outer.zip", 1L}));
        when(bookFileRepository.findArchivesWithActiveGenericContainerEntries(7L)).thenReturn(List.of());

        InpxArchiveScanner.Discovery discovery = scanner.discover(7L, root.toString());

        assertThat(discovery.candidates()).isEmpty();
        assertThat(discovery.totalEntries()).isZero();
    }

    @Test
    void preservesTheExactSourceForABookInAnOuterArchiveFolder(@TempDir Path root) throws IOException {
        createArchive(root.resolve("outer.zip"), "folder/book.pdf");
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of());

        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(scanner.discover(7L, root.toString()), books::add, () -> false);

        assertThat(books).singleElement().satisfies(book -> {
            assertThat(book.getFileName()).isEqualTo("book");
            assertThat(book.getSourceArchiveEntry()).isEqualTo("folder/book.pdf");
        });
    }

    @Test
    void keepsComicArchivesAsBooksAndSkipsUnreadableNestedContainers(@TempDir Path root) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(root.resolve("outer.zip")))) {
            putEntry(output, "comic.cbz", zipBytes("page.jpg", "image"));
            putEntry(output, "broken.zip", "not-a-zip".getBytes(StandardCharsets.UTF_8));
            putEntry(output, "direct.pdf", "pdf".getBytes(StandardCharsets.UTF_8));
        }
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of());

        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(scanner.discover(7L, root.toString()), books::add, () -> false);

        assertThat(books).extracting(InpxBookDto::getExtension).containsExactly("cbz", "pdf");
        assertThat(byExtension(books, "cbz").getBookType()).isEqualTo(BookFileType.CBX);
    }

    @Test
    void stopsAnOverDepthBranchWithoutAbortingItsSiblings(@TempDir Path root) throws IOException {
        byte[] tooDeep = zipBytes("book.pdf", "pdf");
        for (int depth = 6; depth >= 1; depth--) {
            tooDeep = zipBytes("level-" + depth + ".zip", tooDeep);
        }
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(root.resolve("outer.zip")))) {
            putEntry(output, "too-deep.zip", tooDeep);
            putEntry(output, "direct.pdf", "pdf".getBytes(StandardCharsets.UTF_8));
        }
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of());

        List<InpxBookDto> books = new ArrayList<>();
        scanner.forEach(scanner.discover(7L, root.toString()), books::add, () -> false);

        assertThat(books).singleElement().satisfies(book -> {
            assertThat(book.getFileName()).isEqualTo("direct");
            assertThat(book.getBookType()).isEqualTo(BookFileType.PDF);
        });
    }

    @Test
    @EnabledIf("org.booklore.service.ArchiveService#isAvailable")
    void discoversALeafInsideNestedSevenZip(@TempDir Path root) throws IOException {
        Path sevenZip = root.resolve("nested.7z");
        byte[] content = "pdf-content".getBytes(StandardCharsets.UTF_8);
        try (SevenZOutputFile output = new SevenZOutputFile(sevenZip.toFile())) {
            SevenZArchiveEntry entry = new SevenZArchiveEntry();
            entry.setName("folder/book.pdf");
            entry.setSize(content.length);
            output.putArchiveEntry(entry);
            output.write(content);
            output.closeArchiveEntry();
        }
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(root.resolve("outer.zip")))) {
            putEntry(output, "nested.7z", Files.readAllBytes(sevenZip));
        }
        Files.delete(sevenZip);
        when(bookFileRepository.countArchiveEntriesByLibraryId(7L)).thenReturn(List.of());
        ArchiveEntryMetadataRecognizer recognizer = new ArchiveEntryMetadataRecognizer(
                metadataExtractorFactory, docMetadataExtractor, new InpxFilenameMetadataParser());
        InpxArchiveScanner nativeScanner = new InpxArchiveScanner(bookFileRepository, fb2MetadataExtractor,
                recognizer, new ArchiveService(), Runnable::run);

        List<InpxBookDto> books = new ArrayList<>();
        nativeScanner.forEach(nativeScanner.discover(7L, root.toString()), books::add, () -> false);

        assertThat(books).singleElement().satisfies(book -> {
            assertThat(book.getFileName()).isEqualTo("book");
            assertThat(book.getBookType()).isEqualTo(BookFileType.PDF);
            assertThat(NestedArchiveLocator.decode(book.getSourceArchiveEntry()))
                    .containsExactly("nested.7z", "folder/book.pdf");
        });
    }

    private InpxBookDto byExtension(List<InpxBookDto> books, String extension) {
        return books.stream().filter(book -> extension.equals(book.getExtension())).findFirst().orElseThrow();
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
    void populatesFileSizeByTheResolvedLegacyName(@TempDir Path root) throws IOException {
        byte[] content = new byte[2500];
        ZipCharsetTestFixtures.write(root.resolve("books.zip"), "IBM866", false,
                UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Пушкин.fb2", content));
        InpxBookDto book = InpxBookDto.builder()
                .archiveName("books.zip")
                .fileName("Пушкин")
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

    @Test
    void listsMetadataImmediatelyAndCoalescesBackgroundInspection(@TempDir Path root) throws IOException {
        createArchive(root.resolve("books.zip"), "1.fb2", "2.fb2");
        QueuedTaskExecutor executor = new QueuedTaskExecutor();
        ArchiveEntryMetadataRecognizer recognizer = new ArchiveEntryMetadataRecognizer(
                metadataExtractorFactory, docMetadataExtractor, new InpxFilenameMetadataParser());
        InpxArchiveScanner asynchronousScanner = new InpxArchiveScanner(
                bookFileRepository, fb2MetadataExtractor, recognizer, archiveService, executor);

        assertThat(asynchronousScanner.listArchiveMetadata(root.toString()))
                .singleElement()
                .extracting(InpxArchiveScanner.ArchiveFile::entryCount)
                .isNull();
        asynchronousScanner.listArchiveMetadata(root.toString());

        assertThat(executor.size()).isOne();
        assertThat(asynchronousScanner.activeInspectionCount()).isOne();

        executor.runNext();

        assertThat(asynchronousScanner.listArchiveMetadata(root.toString()))
                .singleElement()
                .extracting(InpxArchiveScanner.ArchiveFile::entryCount)
                .isEqualTo(2L);
        assertThat(asynchronousScanner.activeInspectionCount()).isZero();
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

    private byte[] zipBytes(String entryName, String content) throws IOException {
        return zipBytes(entryName, content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] zipBytes(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            putEntry(output, entryName, content);
        }
        return bytes.toByteArray();
    }

    private void putEntry(ZipOutputStream output, String name, byte[] content) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private static final class QueuedTaskExecutor implements TaskExecutor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            tasks.remove().run();
        }
    }
}
