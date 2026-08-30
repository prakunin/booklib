package org.booklore.service.enrichment.catalog;

import org.booklore.model.dto.inpx.LocalCatalogBookViewDto;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.MetadataField;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.BookMetadataFieldSourceService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalCatalogBookViewServiceTest {

    private static final long BOOK_ID = 41L;
    private static final long LIBRARY_ID = 19L;
    private static final String ARCHIVE = "f.fb2-352350-355443.zip";
    private static final String ENTRY = "354924.fb2";

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final LocalCatalogSource catalogSource = mock(LocalCatalogSource.class);
    private final BookMetadataFieldSourceService fieldSourceService = mock(BookMetadataFieldSourceService.class);

    private final LocalCatalogBookViewService service =
            new LocalCatalogBookViewService(bookRepository, catalogSource, fieldSourceService);

    private BookEntity book(BookFileEntity... files) {
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .bookId(BOOK_ID)
                .authors(new ArrayList<>(List.of(AuthorEntity.builder().name("Хэндлер Дэниел").build())))
                .build();
        return BookEntity.builder()
                .id(BOOK_ID)
                .library(LibraryEntity.builder().id(LIBRARY_ID).build())
                .metadata(metadata)
                .bookFiles(new java.util.HashSet<>(List.of(files)))
                .build();
    }

    private BookFileEntity archivedFile() {
        return BookFileEntity.builder().sourceArchive(ARCHIVE).sourceArchiveEntry(ENTRY).build();
    }

    private void stubEmptyCatalog() {
        when(catalogSource.isAvailable(LIBRARY_ID)).thenReturn(true);
        lenient().when(catalogSource.lookupBookMetadata(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(catalogSource.lookupDescription(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(catalogSource.lookupReviews(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        lenient().when(catalogSource.lookupContainingCompilations(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        lenient().when(catalogSource.lookupCompilation(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        lenient().when(catalogSource.lookupAuthorBio(anyLong(), anyString())).thenReturn(Optional.empty());
        lenient().when(fieldSourceService.sourcesForBook(any())).thenReturn(Map.of());
    }

    @Nested
    class WithoutACatalogKey {

        @Test
        void reportsUnavailableWhenNoFileCameOutOfAnArchive() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID))
                    .thenReturn(Optional.of(book(BookFileEntity.builder().build())));

            LocalCatalogBookViewDto view = service.view(BOOK_ID);

            assertThat(view.available()).isFalse();
            assertThat(view.reviews()).isEmpty();
            assertThat(view.fieldsFromCatalog()).isEmpty();
        }

        @Test
        void reportsUnavailableWhenTheLibraryHasNoCatalog() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID))
                    .thenReturn(Optional.of(book(archivedFile())));
            when(catalogSource.isAvailable(LIBRARY_ID)).thenReturn(false);

            assertThat(service.view(BOOK_ID).available()).isFalse();
        }
    }

    @Nested
    class WithACatalogEntry {

        @Test
        void returnsWhatTheCatalogHoldsKeyedOnTheArchiveAndEntry() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID))
                    .thenReturn(Optional.of(book(archivedFile())));
            stubEmptyCatalog();
            when(catalogSource.lookupBookMetadata(LIBRARY_ID, ARCHIVE, ENTRY))
                    .thenReturn(Optional.of(new CatalogBookMetadata("Скверное начало", List.of("Сникет Лемони"), "ru")));
            when(catalogSource.lookupDescription(LIBRARY_ID, ARCHIVE, ENTRY))
                    .thenReturn(Optional.of("Аннотация из каталога"));
            when(catalogSource.lookupReviews(LIBRARY_ID, ARCHIVE, ENTRY))
                    .thenReturn(List.of(new CatalogReview("reader", "хорошая книга", Instant.EPOCH)));

            LocalCatalogBookViewDto view = service.view(BOOK_ID);

            assertThat(view.available()).isTrue();
            assertThat(view.sourceArchive()).isEqualTo(ARCHIVE);
            assertThat(view.sourceArchiveEntry()).isEqualTo(ENTRY);
            assertThat(view.title()).isEqualTo("Скверное начало");
            assertThat(view.authors()).containsExactly("Сникет Лемони");
            assertThat(view.language()).isEqualTo("ru");
            assertThat(view.description()).isEqualTo("Аннотация из каталога");
            assertThat(view.reviewCount()).isEqualTo(1);
            assertThat(view.reviews()).singleElement()
                    .extracting(LocalCatalogBookViewDto.Review::body)
                    .isEqualTo("хорошая книга");
        }

        @Test
        void reportsTheFullReviewCountEvenWhenTheListIsCapped() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID))
                    .thenReturn(Optional.of(book(archivedFile())));
            stubEmptyCatalog();
            when(catalogSource.lookupReviews(LIBRARY_ID, ARCHIVE, ENTRY)).thenReturn(
                    IntStream.range(0, 120)
                            .mapToObj(index -> new CatalogReview("reader" + index, "body" + index, Instant.EPOCH))
                            .toList());

            LocalCatalogBookViewDto view = service.view(BOOK_ID);

            assertThat(view.reviewCount()).isEqualTo(120);
            assertThat(view.reviews()).hasSize(50);
        }

        @Test
        void resolvesCompilationTitlesOnBothSidesOfTheRelationship() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID))
                    .thenReturn(Optional.of(book(archivedFile())));
            stubEmptyCatalog();
            when(catalogSource.lookupContainingCompilations(LIBRARY_ID, ARCHIVE, ENTRY))
                    .thenReturn(List.of(new CompilationMembership("omnibus.zip", "1.fb2", 3)));
            when(catalogSource.lookupCompilation(LIBRARY_ID, ARCHIVE, ENTRY))
                    .thenReturn(List.of(new CompilationPart("parts.zip", "9.fb2", 1)));
            when(catalogSource.lookupBookMetadata(LIBRARY_ID, "omnibus.zip", "1.fb2"))
                    .thenReturn(Optional.of(new CatalogBookMetadata("Сборник", List.of(), "ru")));
            when(catalogSource.lookupBookMetadata(LIBRARY_ID, "parts.zip", "9.fb2"))
                    .thenReturn(Optional.empty());

            LocalCatalogBookViewDto view = service.view(BOOK_ID);

            assertThat(view.containingCompilations()).singleElement().satisfies(ref -> {
                assertThat(ref.title()).isEqualTo("Сборник");
                assertThat(ref.part()).isEqualTo(3);
            });
            // A relationship the catalog records but has no listing row for still shows up, untitled,
            // because dropping it would understate what the catalog knows.
            assertThat(view.compilationParts()).singleElement().satisfies(ref -> {
                assertThat(ref.entryName()).isEqualTo("9.fb2");
                assertThat(ref.title()).isNull();
            });
        }

        @Test
        void looksBiographiesUpUnderBothTheStoredAndTheCatalogSpellingOfAnAuthor() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID))
                    .thenReturn(Optional.of(book(archivedFile())));
            stubEmptyCatalog();
            when(catalogSource.lookupBookMetadata(LIBRARY_ID, ARCHIVE, ENTRY))
                    .thenReturn(Optional.of(new CatalogBookMetadata(null, List.of("Сникет Лемони"), null)));
            when(catalogSource.lookupAuthorBio(LIBRARY_ID, "Сникет Лемони"))
                    .thenReturn(Optional.of("биография"));

            LocalCatalogBookViewDto view = service.view(BOOK_ID);

            assertThat(view.authorBios()).singleElement().satisfies(bio -> {
                assertThat(bio.authorName()).isEqualTo("Сникет Лемони");
                assertThat(bio.biography()).isEqualTo("биография");
            });
        }

        @Test
        void reportsOnlyTheFieldsWhoseRecordedProvenanceIsTheCatalog() {
            when(bookRepository.findByIdWithBookFiles(BOOK_ID))
                    .thenReturn(Optional.of(book(archivedFile())));
            stubEmptyCatalog();
            when(fieldSourceService.sourcesForBook(BOOK_ID)).thenReturn(Map.of(
                    MetadataField.DESCRIPTION, MetadataProvider.FlibustaLocal,
                    MetadataField.LANGUAGE, MetadataProvider.FlibustaLocal,
                    MetadataField.TITLE, MetadataProvider.GoodReads));

            LocalCatalogBookViewDto view = service.view(BOOK_ID);

            assertThat(view.fieldsFromCatalog())
                    .isEqualTo(Set.of(MetadataField.DESCRIPTION, MetadataField.LANGUAGE));
        }
    }
}
