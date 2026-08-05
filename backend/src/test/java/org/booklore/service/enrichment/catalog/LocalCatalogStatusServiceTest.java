package org.booklore.service.enrichment.catalog;

import org.booklore.model.dto.inpx.LocalCatalogStatusDto;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.BookReviewRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalCatalogStatusServiceTest {

    private static final long LIBRARY_ID = 19L;

    private final LocalCatalogIndexRepository localCatalogIndexRepository = mock(LocalCatalogIndexRepository.class);
    private final BookRepository bookRepository = mock(BookRepository.class);
    private final BookReviewRepository bookReviewRepository = mock(BookReviewRepository.class);
    private final AuthorRepository authorRepository = mock(AuthorRepository.class);

    private final LocalCatalogStatusService service = new LocalCatalogStatusService(
            localCatalogIndexRepository, bookRepository, bookReviewRepository, authorRepository);

    private LibraryEntity library(String sidecarPath) {
        return LibraryEntity.builder().id(LIBRARY_ID).metadataSidecarPath(sidecarPath).build();
    }

    @Nested
    class UnconfiguredLibrary {

        @Test
        void reportsNotConfiguredWithNullPathAndStillReturnsCounts() {
            when(localCatalogIndexRepository.countByLibraryIdAndSourceType(eq(LIBRARY_ID), any()))
                    .thenReturn(0L);
            when(bookRepository.countByLibraryIdNonDeleted(LIBRARY_ID)).thenReturn(5L);
            when(bookRepository.countByLibraryIdNonDeletedWithDescription(LIBRARY_ID)).thenReturn(0L);
            when(bookReviewRepository.countByMetadataProviderAndBookMetadataBookLibraryId(
                    MetadataProvider.FlibustaLocal, LIBRARY_ID)).thenReturn(0L);
            when(authorRepository.countWithNonBlankDescription()).thenReturn(0L);

            LocalCatalogStatusDto status = service.getStatus(library(null));

            assertThat(status.configured()).isFalse();
            assertThat(status.catalogPath()).isNull();
            assertThat(status.totalBooks()).isEqualTo(5L);
        }

        @Test
        void blankSidecarPathAlsoCountsAsUnconfigured() {
            when(localCatalogIndexRepository.countByLibraryIdAndSourceType(eq(LIBRARY_ID), any()))
                    .thenReturn(0L);
            when(bookRepository.countByLibraryIdNonDeleted(LIBRARY_ID)).thenReturn(0L);
            when(bookRepository.countByLibraryIdNonDeletedWithDescription(LIBRARY_ID)).thenReturn(0L);
            when(bookReviewRepository.countByMetadataProviderAndBookMetadataBookLibraryId(
                    MetadataProvider.FlibustaLocal, LIBRARY_ID)).thenReturn(0L);
            when(authorRepository.countWithNonBlankDescription()).thenReturn(0L);

            LocalCatalogStatusDto status = service.getStatus(library("   "));

            assertThat(status.configured()).isFalse();
        }
    }

    @Nested
    class ConfiguredButNeverIndexedLibrary {

        @Test
        void everySourceTypeKeyIsPresentWithZero() {
            when(localCatalogIndexRepository.countByLibraryIdAndSourceType(eq(LIBRARY_ID), any()))
                    .thenReturn(0L);
            when(bookRepository.countByLibraryIdNonDeleted(LIBRARY_ID)).thenReturn(10L);
            when(bookRepository.countByLibraryIdNonDeletedWithDescription(LIBRARY_ID)).thenReturn(0L);
            when(bookReviewRepository.countByMetadataProviderAndBookMetadataBookLibraryId(
                    MetadataProvider.FlibustaLocal, LIBRARY_ID)).thenReturn(0L);
            when(authorRepository.countWithNonBlankDescription()).thenReturn(0L);

            LocalCatalogStatusDto status = service.getStatus(
                    library("/data/catalog/fb2.Flibusta.Net.FLibrary.etc"));

            assertThat(status.configured()).isTrue();
            assertThat(status.catalogPath()).isEqualTo("/data/catalog/fb2.Flibusta.Net.FLibrary.etc");
            assertThat(status.indexedEntries()).containsOnlyKeys(
                    LocalCatalogSourceType.REVIEW,
                    LocalCatalogSourceType.AUTHOR_BIO,
                    LocalCatalogSourceType.COMPILATION,
                    LocalCatalogSourceType.COMPILATION_PART,
                    LocalCatalogSourceType.LANGUAGE);
            assertThat(status.indexedEntries().values()).containsOnly(0L);
        }
    }

    @Nested
    class PopulatedLibrary {

        @Test
        void eachCountComesFromItsOwnRepositoryCall() {
            when(localCatalogIndexRepository.countByLibraryIdAndSourceType(LIBRARY_ID, LocalCatalogSourceType.REVIEW))
                    .thenReturn(101L);
            when(localCatalogIndexRepository.countByLibraryIdAndSourceType(LIBRARY_ID, LocalCatalogSourceType.AUTHOR_BIO))
                    .thenReturn(202L);
            when(localCatalogIndexRepository.countByLibraryIdAndSourceType(LIBRARY_ID, LocalCatalogSourceType.COMPILATION))
                    .thenReturn(303L);
            when(localCatalogIndexRepository.countByLibraryIdAndSourceType(LIBRARY_ID, LocalCatalogSourceType.COMPILATION_PART))
                    .thenReturn(404L);
            when(localCatalogIndexRepository.countByLibraryIdAndSourceType(LIBRARY_ID, LocalCatalogSourceType.LANGUAGE))
                    .thenReturn(505L);
            when(bookRepository.countByLibraryIdNonDeleted(LIBRARY_ID)).thenReturn(9001L);
            when(bookRepository.countByLibraryIdNonDeletedWithDescription(LIBRARY_ID)).thenReturn(7002L);
            when(bookReviewRepository.countByMetadataProviderAndBookMetadataBookLibraryId(
                    MetadataProvider.FlibustaLocal, LIBRARY_ID)).thenReturn(6003L);
            when(authorRepository.countWithNonBlankDescription()).thenReturn(5004L);

            LocalCatalogStatusDto status = service.getStatus(
                    library("/data/catalog/fb2.Flibusta.Net.FLibrary.etc"));

            assertThat(status.configured()).isTrue();
            assertThat(status.catalogPath()).isEqualTo("/data/catalog/fb2.Flibusta.Net.FLibrary.etc");
            assertThat(status.indexedEntries())
                    .containsEntry(LocalCatalogSourceType.REVIEW, 101L)
                    .containsEntry(LocalCatalogSourceType.AUTHOR_BIO, 202L)
                    .containsEntry(LocalCatalogSourceType.COMPILATION, 303L)
                    .containsEntry(LocalCatalogSourceType.COMPILATION_PART, 404L)
                    .containsEntry(LocalCatalogSourceType.LANGUAGE, 505L);
            assertThat(status.totalBooks()).isEqualTo(9001L);
            assertThat(status.booksWithDescription()).isEqualTo(7002L);
            assertThat(status.localReviews()).isEqualTo(6003L);
            assertThat(status.authorsWithBiography()).isEqualTo(5004L);
        }
    }
}
