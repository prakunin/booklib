package org.booklore.service.enrichment.catalog;

import org.booklore.model.entity.LocalCatalogIndexEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlibustaCatalogSourceBookMetadataTest {

    private static final long LIBRARY_ID = 7L;
    private static final String ARCHIVE = "books.zip";
    private static final String ENTRY = "1.fb2";
    private static final String KEY = ARCHIVE + "#" + ENTRY;

    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);
    private final LocalCatalogIndexRepository indexRepository = mock(LocalCatalogIndexRepository.class);
    private final ArchiveService archiveService = mock(ArchiveService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private FlibustaCatalogSource source;

    @BeforeEach
    void setUp() {
        source = new FlibustaCatalogSource(libraryRepository, indexRepository,
                new FlibustaCatalogLayout(), new FlibustaAnnotationParser(),
                new FlibustaReviewParser(objectMapper), archiveService, objectMapper);
    }

    @Test
    void readsTitleAuthorsAndLanguageFromTheCurrentPayload() {
        indexed(objectMapper.writeValueAsString(new CatalogBookMetadata(
                "Correct title", List.of("Correct Author", "Second Author"), "ru")));

        assertThat(source.lookupBookMetadata(LIBRARY_ID, ARCHIVE, ENTRY))
                .contains(new CatalogBookMetadata(
                        "Correct title", List.of("Correct Author", "Second Author"), "ru"));
        assertThat(source.lookupLanguage(LIBRARY_ID, ARCHIVE, ENTRY)).contains("ru");
    }

    @Test
    void readsLegacyPlainLanguageWithoutInventingIdentity() {
        indexed("ru");

        assertThat(source.lookupBookMetadata(LIBRARY_ID, ARCHIVE, ENTRY))
                .contains(new CatalogBookMetadata(null, List.of(), "ru"));
        assertThat(source.lookupLanguage(LIBRARY_ID, ARCHIVE, ENTRY)).contains("ru");
    }

    @Test
    void rejectsMalformedJsonWithoutFallingBackToAFalseLanguage() {
        indexed("{not-json");

        assertThat(source.lookupBookMetadata(LIBRARY_ID, ARCHIVE, ENTRY)).isEmpty();
        assertThat(source.lookupLanguage(LIBRARY_ID, ARCHIVE, ENTRY)).isEmpty();
    }

    @Test
    void returnsNothingWithoutAnExactArchiveEntryMatch() {
        when(indexRepository.findByLibraryIdAndSourceTypeAndEntryKey(
                LIBRARY_ID, LocalCatalogSourceType.LANGUAGE, KEY)).thenReturn(Optional.empty());

        assertThat(source.lookupBookMetadata(LIBRARY_ID, ARCHIVE, ENTRY)).isEmpty();
    }

    private void indexed(String payload) {
        when(indexRepository.findByLibraryIdAndSourceTypeAndEntryKey(
                LIBRARY_ID, LocalCatalogSourceType.LANGUAGE, KEY))
                .thenReturn(Optional.of(LocalCatalogIndexEntity.builder()
                        .libraryId(LIBRARY_ID)
                        .sourceType(LocalCatalogSourceType.LANGUAGE)
                        .entryKey(KEY)
                        .payload(payload)
                        .build()));
    }
}
