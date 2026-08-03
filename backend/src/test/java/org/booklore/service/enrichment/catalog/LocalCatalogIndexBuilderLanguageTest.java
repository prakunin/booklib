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
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalCatalogIndexBuilderLanguageTest {

    @TempDir
    Path catalogRoot;

    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);
    private final LocalCatalogIndexRepository indexRepository = mock(LocalCatalogIndexRepository.class);
    private final ArchiveService archiveService = mock(ArchiveService.class);

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

        builder = new LocalCatalogIndexBuilder(libraryRepository, indexRepository,
                new FlibustaCatalogLayout(), new FlibustaCompilationParser(new ObjectMapper()),
                new FlibustaContentsParser(), archiveService, new ObjectMapper());
    }

    @Test
    void indexesOneLanguageRowPerListedBook() {
        LocalCatalogIndexBuilder.IndexResult result = builder.rebuild(7L);

        assertThat(result.languages()).isEqualTo(2);

        ArgumentCaptor<List<LocalCatalogIndexEntity>> saved = ArgumentCaptor.captor();
        verify(indexRepository, org.mockito.Mockito.atLeastOnce()).saveAll(saved.capture());
        List<LocalCatalogIndexEntity> languageRows = saved.getAllValues().stream()
                .flatMap(List::stream)
                .filter(row -> row.getSourceType() == LocalCatalogSourceType.LANGUAGE)
                .toList();

        assertThat(languageRows).extracting(
                        LocalCatalogIndexEntity::getEntryKey, LocalCatalogIndexEntity::getPayload)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("f.fb2-173909-177717.zip#174393.fb2", "ru"),
                        org.assertj.core.groups.Tuple.tuple("fb2-091841-104214.zip#95887.fb2", "zh"));
    }

    @Test
    void clearsPreviousLanguageRowsBeforeIndexing() {
        builder.rebuild(7L);

        verify(indexRepository).deleteByLibraryIdAndSourceType(7L, LocalCatalogSourceType.LANGUAGE);
    }
}
