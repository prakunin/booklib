package org.booklore.service.enrichment.catalog;

import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LocalCatalogIndexEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalCatalogIndexBuilderLanguageTest {

    @TempDir
    Path catalogRoot;

    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);
    private final LocalCatalogIndexRepository indexRepository = mock(LocalCatalogIndexRepository.class);
    private final ArchiveService archiveService = mock(ArchiveService.class);

    /**
     * What {@code saveAll} was actually invoked with, snapshotted at call time rather than captured by
     * reference: {@link LocalCatalogIndexBuilder} reuses and clears its batch buffer immediately after
     * handing it to the repository, so a plain {@code ArgumentCaptor} would only ever observe the
     * buffer's post-clear (empty) state. A {@code doAnswer} runs while the buffer is still populated, so
     * copying it there is what makes the saved rows observable at all.
     */
    private final List<LocalCatalogIndexEntity> savedRows = new ArrayList<>();

    private LocalCatalogIndexBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        // matches() requires annotations.7z to exist; the other containers stay absent so this test
        // exercises the language pass alone.
        Files.write(catalogRoot.resolve("annotations.7z"), new byte[]{1});
        Files.write(catalogRoot.resolve("contents.7z"), new byte[]{1});

        LibraryEntity library = new LibraryEntity();
        library.setMetadataSidecarPath(catalogRoot.toString());
        when(libraryRepository.findById(7L)).thenReturn(Optional.of(library));

        when(archiveService.getEntryNames(catalogRoot.resolve("contents.7z")))
                .thenReturn(List.of("ru.txt", "zh.txt"));
        when(archiveService.getEntryBytes(catalogRoot.resolve("contents.7z"), "ru.txt"))
                .thenReturn("Толстой\tВойна и мир\t\tf.fb2-173909-177717.zip\t174393.fb2\n"
                        .getBytes(StandardCharsets.UTF_8));
        when(archiveService.getEntryBytes(catalogRoot.resolve("contents.7z"), "zh.txt"))
                .thenReturn("Жун\tWolf Totem\t\tfb2-091841-104214.zip\t95887.fb2\n"
                        .getBytes(StandardCharsets.UTF_8));

        when(indexRepository.<LocalCatalogIndexEntity>saveAll(any()))
                .thenAnswer(invocation -> {
                    List<LocalCatalogIndexEntity> batch = invocation.getArgument(0);
                    savedRows.addAll(batch);
                    return List.copyOf(batch);
                });

        builder = new LocalCatalogIndexBuilder(libraryRepository, indexRepository,
                new FlibustaCatalogLayout(), new FlibustaCompilationParser(new ObjectMapper()),
                new FlibustaContentsParser(), archiveService, new ObjectMapper());
    }

    @Test
    void indexesOneLanguageRowPerListedBook() {
        LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

        assertThat(result.languages()).isEqualTo(2);

        List<LocalCatalogIndexEntity> languageRows = savedRows.stream()
                .filter(row -> row.getSourceType() == LocalCatalogSourceType.LANGUAGE)
                .toList();

        assertThat(languageRows).extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsExactlyInAnyOrder(
                        "f.fb2-173909-177717.zip#174393.fb2",
                        "fb2-091841-104214.zip#95887.fb2");
        assertThat(readPayload(languageRows, "f.fb2-173909-177717.zip#174393.fb2"))
                .isEqualTo(new CatalogBookMetadata("Война и мир", List.of("Толстой"), "ru"));
        assertThat(readPayload(languageRows, "fb2-091841-104214.zip#95887.fb2"))
                .isEqualTo(new CatalogBookMetadata("Wolf Totem", List.of("Жун"), "zh"));
    }

    @Test
    void clearsPreviousLanguageRowsBeforeIndexing() {
        builder.rebuild(7L);

        verify(indexRepository).deleteByLibraryIdAndSourceType(7L, LocalCatalogSourceType.LANGUAGE);
    }

    /**
     * The only duplication the shipped catalog actually has: 75 keys are listed twice inside one
     * language listing, as byte-identical rows. Every one of those rows used to become its own index
     * row and collide on {@code uk_local_catalog_index}. Nothing is lost by keeping one — the two rows
     * carry the same language.
     */
    @Test
    void aBookListedTwiceInOneListingProducesOneRow() throws Exception {
        when(archiveService.getEntryNames(catalogRoot.resolve("contents.7z"))).thenReturn(List.of("ru.txt"));
        when(archiveService.getEntryBytes(catalogRoot.resolve("contents.7z"), "ru.txt"))
                .thenReturn(("Толстой\tВойна и мир\t\tf.fb2-173909-177717.zip\t174393.fb2\n"
                        + "Толстой\tВойна и мир\t\tf.fb2-173909-177717.zip\t174393.fb2\n")
                        .getBytes(StandardCharsets.UTF_8));

        LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

        assertThat(result.languages()).isEqualTo(1);
        assertThat(savedRows.stream().filter(row -> row.getSourceType() == LocalCatalogSourceType.LANGUAGE).toList())
                .extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsExactly("f.fb2-173909-177717.zip#174393.fb2");
    }

    /**
     * No key in the shipped catalog is listed under two languages — measured across all 75 listings,
     * zero of the 702,291 keys cross a language boundary — so this shape is defensive rather than
     * observed. Should the data ever change, the first listing wins deterministically instead of the
     * rebuild aborting, and the conflict is logged rather than swallowed. The listings deliberately
     * carry different languages so an implementation that kept the later one fails here.
     */
    @Test
    void keepsTheFirstListingWhenAKeyIsListedUnderTwoLanguages() throws Exception {
        when(archiveService.getEntryBytes(catalogRoot.resolve("contents.7z"), "zh.txt"))
                .thenReturn("Толстой\tWar and Peace\t\tf.fb2-173909-177717.zip\t174393.fb2\n"
                        .getBytes(StandardCharsets.UTF_8));

        LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

        assertThat(result.languages()).isEqualTo(1);
        assertThat(savedRows.stream().filter(row -> row.getSourceType() == LocalCatalogSourceType.LANGUAGE).toList())
                .extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsExactly("f.fb2-173909-177717.zip#174393.fb2");
        List<LocalCatalogIndexEntity> languageRows = savedRows.stream()
                .filter(row -> row.getSourceType() == LocalCatalogSourceType.LANGUAGE)
                .toList();
        assertThat(readPayload(languageRows, "f.fb2-173909-177717.zip#174393.fb2"))
                .isEqualTo(new CatalogBookMetadata("Война и мир", List.of("Толстой"), "ru"));
    }

    @Test
    void rebuildingTwiceLeavesTheSameLanguageRowsNotDuplicates() {
        builder.rebuild(7L);
        List<String> firstRunKeys = savedRows.stream()
                .filter(row -> row.getSourceType() == LocalCatalogSourceType.LANGUAGE)
                .map(LocalCatalogIndexEntity::getEntryKey)
                .toList();
        savedRows.clear();

        builder.rebuild(7L);

        assertThat(savedRows.stream().filter(row -> row.getSourceType() == LocalCatalogSourceType.LANGUAGE).toList())
                .extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsExactlyInAnyOrderElementsOf(firstRunKeys);
    }

    /**
     * {@link FlibustaContentsParser#parse} wraps its whole read loop in a broad {@code catch (Exception)},
     * so a {@code saveAll} failure thrown from inside the consumer it drives would otherwise be swallowed
     * there and logged only as a row-read warning, with the pass reporting success regardless. This drives
     * enough rows through a single listing to force the batch past {@code BATCH_SIZE} — triggering the
     * flush that happens *inside* the parser's callback, not the harmless final one after it returns — and
     * asserts the resulting database failure is not silently absorbed.
     */
    @Test
    void doesNotReportSuccessWhenSavingLanguageRowsFails() throws Exception {
        StringBuilder hugeListing = new StringBuilder();
        for (int i = 0; i < 1_500; i++) {
            hugeListing.append("Author\tTitle\t\tarchive-").append(i).append(".zip\tentry-").append(i)
                    .append(".fb2\n");
        }
        when(archiveService.getEntryNames(catalogRoot.resolve("contents.7z"))).thenReturn(List.of("ru.txt"));
        when(archiveService.getEntryBytes(catalogRoot.resolve("contents.7z"), "ru.txt"))
                .thenReturn(hugeListing.toString().getBytes(StandardCharsets.UTF_8));

        // doThrow(...).when(...), not a second when(...).thenThrow(...): the latter would re-invoke
        // saveAll(any()) to record the call, which would run the thenAnswer already stubbed in
        // setUp() with any()'s null placeholder as the argument and NPE before the override even
        // takes effect.
        RuntimeException dbFailure = new RuntimeException("simulated database failure");
        doThrow(dbFailure).when(indexRepository).saveAll(any());

        assertThatThrownBy(() -> builder.rebuild(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(dbFailure);
    }

    @Test
    void writesTheVersionMarkerOnlyAfterTheContentsRows() {
        builder.rebuild(7L);

        assertThat(savedRows.getLast().getSourceType()).isEqualTo(LocalCatalogSourceType.INDEX_VERSION);
        assertThat(savedRows.getLast().getPayload()).isEqualTo("2");
    }

    @Test
    void doesNotWriteTheVersionMarkerWhenContentsCannotBeListed() throws Exception {
        when(archiveService.getEntryNames(catalogRoot.resolve("contents.7z")))
                .thenThrow(new IOException("damaged archive"));

        builder.rebuild(7L);

        assertThat(savedRows).noneMatch(row -> row.getSourceType() == LocalCatalogSourceType.INDEX_VERSION);
    }

    @Test
    void doesNotWriteTheVersionMarkerWhenAContentsListingCannotBeRead() throws Exception {
        when(archiveService.getEntryBytes(catalogRoot.resolve("contents.7z"), "ru.txt"))
                .thenThrow(new IOException("damaged listing"));

        builder.rebuild(7L);

        assertThat(savedRows).noneMatch(row -> row.getSourceType() == LocalCatalogSourceType.INDEX_VERSION);
    }

    @Test
    void doesNotWriteTheVersionMarkerWhenContentsArchiveIsMissing() throws Exception {
        Files.delete(catalogRoot.resolve("contents.7z"));

        builder.rebuild(7L);

        assertThat(savedRows).noneMatch(row -> row.getSourceType() == LocalCatalogSourceType.INDEX_VERSION);
    }

    private CatalogBookMetadata readPayload(List<LocalCatalogIndexEntity> rows, String key) {
        String payload = rows.stream()
                .filter(row -> key.equals(row.getEntryKey()))
                .findFirst()
                .orElseThrow()
                .getPayload();
        return new ObjectMapper().readValue(payload, CatalogBookMetadata.class);
    }
}
