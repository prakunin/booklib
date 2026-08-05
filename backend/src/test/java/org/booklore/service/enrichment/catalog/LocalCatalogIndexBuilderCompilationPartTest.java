package org.booklore.service.enrichment.catalog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LocalCatalogIndexEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import tools.jackson.core.type.TypeReference;

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
import static org.mockito.Mockito.times;
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
     * The setup's part key {@code b.zip#477830.fb2} appears under a single omnibus, so this proves the
     * common (55%) case still yields exactly one membership.
     */
    @Test
    void aWorkInOneOmnibusHasExactlyOneMembership() {
        builder.rebuild(7L);

        List<CompilationMembership> memberships = membershipsOf("b.zip#477830.fb2");

        assertThat(memberships).containsExactly(new CompilationMembership("omnibus.zip", "13026.fb2", 1));
    }

    /**
     * This is the previously fatal shape: the same part key ({@code shared.zip#work.fb2}) surfaces
     * under three distinct omnibuses at three distinct positions. Before this fix each membership
     * produced its own row under the same unique {@code (library_id, source_type, entry_key)} key,
     * which aborted the whole rebuild with a duplicate-entry error on the real database. Distinct
     * omnibus names and distinct, non-sequential part numbers are used deliberately so that an
     * aggregation which silently keeps only the first membership, or drops a position, fails this
     * assertion instead of passing by accident.
     */
    @Test
    void aWorkInThreeOmnibusesProducesOneRowWithAllThreeMembershipsAndPositions() throws Exception {
        when(archiveService.getEntryBytes(catalogRoot.resolve("compilations.7z"), "compilations.json"))
                .thenReturn("""
                        [{"file": "201.fb2", "folder": "anthology-alpha.zip",
                          "compilation": [{"file": "work.fb2", "folder": "shared.zip", "part": 4}]},
                         {"file": "202.fb2", "folder": "anthology-beta.zip",
                          "compilation": [{"file": "work.fb2", "folder": "shared.zip", "part": 9}]},
                         {"file": "203.fb2", "folder": "anthology-gamma.zip",
                          "compilation": [{"file": "work.fb2", "folder": "shared.zip", "part": 2}]}]
                        """.getBytes(StandardCharsets.UTF_8));

        builder.rebuild(7L);

        assertThat(savedRowsOfType(LocalCatalogSourceType.COMPILATION_PART))
                .extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsOnlyOnce("shared.zip#work.fb2");
        assertThat(membershipsOf("shared.zip#work.fb2")).containsExactlyInAnyOrder(
                new CompilationMembership("anthology-alpha.zip", "201.fb2", 4),
                new CompilationMembership("anthology-beta.zip", "202.fb2", 9),
                new CompilationMembership("anthology-gamma.zip", "203.fb2", 2));
    }

    /**
     * Same repeating-key shape as above, framed as the rebuild-level regression test: the rebuild that
     * used to throw {@code Duplicate entry '19-COMPILATION_PART-…'} and leave the index empty must now
     * both complete and leave a non-empty index behind.
     */
    @Test
    void rebuildOverRepeatingPartKeysCompletesInsteadOfAborting() throws Exception {
        when(archiveService.getEntryBytes(catalogRoot.resolve("compilations.7z"), "compilations.json"))
                .thenReturn("""
                        [{"file": "201.fb2", "folder": "anthology-alpha.zip",
                          "compilation": [{"file": "work.fb2", "folder": "shared.zip", "part": 4}]},
                         {"file": "202.fb2", "folder": "anthology-beta.zip",
                          "compilation": [{"file": "work.fb2", "folder": "shared.zip", "part": 9}]}]
                        """.getBytes(StandardCharsets.UTF_8));

        LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

        assertThat(result.compilations()).isPositive();
        assertThat(savedRowsOfType(LocalCatalogSourceType.COMPILATION_PART)).isNotEmpty();
    }

    /**
     * The fifth and last instance of this branch's defect 2 — "one row per sighting of a key, so the
     * second sighting collides with {@code uk_local_catalog_index} and aborts the whole rebuild". The
     * four other passes were fixed; the forward {@code COMPILATION} pass was not, and it is the one
     * whose abort is unrecoverable: it happens after {@code REVIEW}/{@code AUTHOR_BIO} are written and
     * before {@code indexLanguages} runs, and {@code isIndexed} is satisfied by the rows already there,
     * so {@code ensureIndexed} never repairs it and {@code LANGUAGE}/{@code COMPILATION} stay
     * permanently empty for that library.
     * <p>
     * The same {@code (folder, file)} pair is listed twice with different part lists, so a fix that
     * merely deduplicated the row while still counting both sightings' parts would fail the membership
     * assertion rather than pass by accident.
     */
    @Test
    void writesOneForwardRowWhenTheSameCompilationIsListedTwice() throws Exception {
        when(archiveService.getEntryBytes(catalogRoot.resolve("compilations.7z"), "compilations.json"))
                .thenReturn("""
                        [{"file": "13026.fb2", "folder": "omnibus.zip",
                          "compilation": [{"file": "13023.fb2", "folder": "a.zip", "part": 0}]},
                         {"file": "13026.fb2", "folder": "omnibus.zip",
                          "compilation": [{"file": "13023.fb2", "folder": "a.zip", "part": 0}]}]
                        """.getBytes(StandardCharsets.UTF_8));

        LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

        assertThat(savedRowsOfType(LocalCatalogSourceType.COMPILATION))
                .extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsExactly("omnibus.zip#13026.fb2");
        assertThat(result.compilations()).isEqualTo(1);
        assertThat(membershipsOf("a.zip#13023.fb2"))
                .containsExactly(new CompilationMembership("omnibus.zip", "13026.fb2", 0));
    }

    /**
     * The reverse rows are a source type of their own — 78,907 of them in the shipped catalog — and
     * they can zero out independently of the forward rows: a document of compilations that names no
     * usable part keys writes {@code COMPILATION} rows and no {@code COMPILATION_PART} rows at all.
     * They are therefore counted separately rather than folded into {@code compilations()}.
     */
    @Test
    void countsReverseRowsSeparatelyFromForwardOnes() {
        LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

        assertThat(result.compilations()).isEqualTo(1);
        assertThat(result.compilationParts()).isEqualTo(2);
    }

    /**
     * The blind spot the four-field summary had: {@code COMPILATION_PART} is 78,907 rows in the shipped
     * catalog and nothing reported on it, so a rebuild that produced none of them looked identical to
     * one that produced them all. It is warned about in its own right now, distinctly from the forward
     * {@code COMPILATION} rows — a substring check would not tell the two warnings apart, so both are
     * asserted on exactly.
     * <p>
     * A compilations document that yields nothing is the reachable way to get here: with the parser's
     * present guards a compilation is only accepted when its own key and at least one of its parts are
     * usable, so forward-positive-and-reverse-zero cannot currently occur. The two counts are still
     * carried and warned about separately, because they are separate source types and nothing but that
     * guard keeps them in step.
     */
    @Test
    void warnsAboutReverseRowsInTheirOwnRight() throws Exception {
        when(archiveService.getEntryBytes(catalogRoot.resolve("compilations.7z"), "compilations.json"))
                .thenReturn("[]".getBytes(StandardCharsets.UTF_8));

        Logger logger = (Logger) LoggerFactory.getLogger(LocalCatalogIndexBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

            assertThat(result.compilations()).isZero();
            assertThat(result.compilationParts()).isZero();
            assertThat(warningsMentioning(appender, LocalCatalogSourceType.COMPILATION_PART)).isNotEmpty();
            assertThat(warningsMentioning(appender, LocalCatalogSourceType.COMPILATION)).isNotEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * Warnings naming exactly this source type. {@code COMPILATION_PART} contains {@code COMPILATION},
     * so a plain {@code contains} would let one warning satisfy an assertion about the other.
     */
    private List<String> warningsMentioning(ListAppender<ILoggingEvent> appender, LocalCatalogSourceType type) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.matches(".*\\b" + type.name() + "\\b.*"))
                .toList();
    }

    /**
     * The builder deletes rows for the source type before it writes, which is what makes rebuilds
     * idempotent; this proves that still holds now that reverse rows are written in a second, batched
     * pass rather than inline with the forward rows.
     */
    @Test
    void rebuildingTwiceLeavesTheSameRowsNotDuplicates() {
        builder.rebuild(7L);
        List<String> firstRunKeys = savedRowsOfType(LocalCatalogSourceType.COMPILATION_PART).stream()
                .map(LocalCatalogIndexEntity::getEntryKey)
                .toList();
        savedRows.clear();

        builder.rebuild(7L);

        assertThat(savedRowsOfType(LocalCatalogSourceType.COMPILATION_PART))
                .extracting(LocalCatalogIndexEntity::getEntryKey)
                .containsExactlyInAnyOrderElementsOf(firstRunKeys);
        verify(indexRepository, times(2))
                .deleteByLibraryIdAndSourceType(7L, LocalCatalogSourceType.COMPILATION_PART);
    }

    private List<CompilationMembership> membershipsOf(String entryKey) {
        LocalCatalogIndexEntity row = savedRowsOfType(LocalCatalogSourceType.COMPILATION_PART).stream()
                .filter(r -> r.getEntryKey().equals(entryKey))
                .findFirst()
                .orElseThrow();
        return new ObjectMapper().readValue(row.getPayload(), new TypeReference<List<CompilationMembership>>() {
        });
    }

    /**
     * {@link FlibustaCompilationParser#parse} wraps its whole read loop in a broad
     * {@code catch (Exception)}, just like {@link FlibustaContentsParser#parse} — so a {@code saveAll}
     * failure thrown from inside the consumer it drives would otherwise be swallowed there and logged
     * only as a parse warning, with the pass reporting success regardless. Reverse ({@code
     * COMPILATION_PART}) rows are now accumulated in memory and written only after {@code parse}
     * returns, so they can no longer be the ones that trigger a save from inside the callback; only the
     * forward ({@code COMPILATION}) rows still are. This drives 1,000 single-part compilations — one
     * forward row apiece — through a single document to push the shared batch to exactly
     * {@code BATCH_SIZE}, triggering the flush that happens *inside* the parser's callback, not the
     * harmless final one after it returns, and asserts the resulting database failure is not silently
     * absorbed.
     */
    @Test
    void doesNotReportSuccessWhenSavingCompilationRowsFails() throws Exception {
        StringBuilder hugeCompilations = new StringBuilder("[");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) {
                hugeCompilations.append(",");
            }
            hugeCompilations.append("""
                    {"file": "%d.fb2", "folder": "omnibus-%d.zip",
                     "compilation": [{"file": "%d-a.fb2", "folder": "a-%d.zip", "part": 0}]}
                    """.formatted(i, i, i, i));
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
