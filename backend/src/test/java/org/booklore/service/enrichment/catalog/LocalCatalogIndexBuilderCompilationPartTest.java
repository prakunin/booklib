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

class LocalCatalogIndexBuilderCompilationPartTest {

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
        Files.write(catalogRoot.resolve("annotations.7z"), new byte[]{1});
        Files.write(catalogRoot.resolve("compilations.7z"), new byte[]{1});

        LibraryEntity library = new LibraryEntity();
        library.setMetadataSidecarPath(catalogRoot.toString());
        when(libraryRepository.findById(7L)).thenReturn(Optional.of(library));

        when(archiveService.getEntryBytes(catalogRoot.resolve("compilations.7z"), "compilations.json"))
                .thenReturn("""
                        [{"file": "13026.fb2", "folder": "omnibus.zip",
                          "compilation": [{"file": "13023.fb2", "folder": "a.zip", "part": 0},
                                          {"file": "477830.fb2", "folder": "b.zip", "part": 1}]}]
                        """.getBytes(StandardCharsets.UTF_8));

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

    private List<LocalCatalogIndexEntity> savedRowsOfType(LocalCatalogSourceType type) {
        return savedRows.stream()
                .filter(row -> row.getSourceType() == type)
                .toList();
    }

    @Test
    void writesOneReverseRowPerPart() {
        builder.rebuild(7L);

        assertThat(savedRowsOfType(LocalCatalogSourceType.COMPILATION_PART))
                .extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsExactlyInAnyOrder("a.zip#13023.fb2", "b.zip#477830.fb2");
    }

    @Test
    void theReverseRowNamesItsOmnibusAndPosition() {
        builder.rebuild(7L);

        LocalCatalogIndexEntity first = savedRowsOfType(LocalCatalogSourceType.COMPILATION_PART).stream()
                .filter(row -> row.getEntryKey().equals("b.zip#477830.fb2"))
                .findFirst()
                .orElseThrow();

        assertThat(first.getPayload()).contains("omnibus.zip").contains("13026.fb2").contains("1");
    }

    @Test
    void keepsTheForwardRowsAsWell() {
        builder.rebuild(7L);

        assertThat(savedRowsOfType(LocalCatalogSourceType.COMPILATION))
                .extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsExactly("omnibus.zip#13026.fb2");
    }

    @Test
    void clearsPreviousReverseRowsBeforeIndexing() {
        builder.rebuild(7L);

        verify(indexRepository).deleteByLibraryIdAndSourceType(7L, LocalCatalogSourceType.COMPILATION_PART);
    }

    /**
     * {@link FlibustaCompilationParser#parse} wraps its whole read loop in a broad
     * {@code catch (Exception)}, just like {@link FlibustaContentsParser#parse} — so a {@code saveAll}
     * failure thrown from inside the consumer it drives would otherwise be swallowed there and logged
     * only as a parse warning, with the pass reporting success regardless. This drives enough
     * compilations through a single document to force the batch past {@code BATCH_SIZE} — each
     * compilation contributes one forward row plus one row per part, so 400 two-part compilations push
     * 1200 rows through — triggering the flush that happens *inside* the parser's callback, not the
     * harmless final one after it returns, and asserts the resulting database failure is not silently
     * absorbed.
     */
    @Test
    void doesNotReportSuccessWhenSavingCompilationRowsFails() throws Exception {
        StringBuilder hugeCompilations = new StringBuilder("[");
        for (int i = 0; i < 400; i++) {
            if (i > 0) {
                hugeCompilations.append(",");
            }
            hugeCompilations.append("""
                    {"file": "%d.fb2", "folder": "omnibus-%d.zip",
                     "compilation": [{"file": "%d-a.fb2", "folder": "a-%d.zip", "part": 0},
                                     {"file": "%d-b.fb2", "folder": "b-%d.zip", "part": 1}]}
                    """.formatted(i, i, i, i, i, i));
        }
        hugeCompilations.append("]");
        when(archiveService.getEntryBytes(catalogRoot.resolve("compilations.7z"), "compilations.json"))
                .thenReturn(hugeCompilations.toString().getBytes(StandardCharsets.UTF_8));

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
}
