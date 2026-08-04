package org.booklore.service.enrichment;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.MetadataFetchJobRepository;
import org.booklore.service.metadata.MetadataRefreshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Writing an author biography has to find the author row the name belongs to, and how it looks that
 * row up decides what the local-catalog backfill costs. {@code findByNameIgnoreCase} compiles to
 * {@code upper(name) = upper(?)}, which no index on {@code author.name} can serve, so every
 * biography scanned all 271,250 authors — 153 ms each, measured, and two thirds of the backfill's
 * wall clock. The names arriving here come from the book's own stored metadata, so the exact lookup
 * is the one that answers, and the scan is only worth paying when it misses.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrichmentApplierAuthorBioTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private AuthorRepository authorRepository;
    @Mock
    private MetadataFetchJobRepository jobRepository;
    @Mock
    private MetadataRefreshService metadataRefreshService;

    private EnrichmentApplier applier;

    @BeforeEach
    void setUp() {
        applier = new EnrichmentApplier(bookRepository, authorRepository, jobRepository,
                metadataRefreshService, new ObjectMapper());
    }

    private EnrichmentContext contextWithBio(String authorName, String bio) {
        EnrichmentContext context = new EnrichmentContext(
                Book.builder().id(11L).build(), 19L, "f.fb2-185838-188548.zip", "185900.fb2",
                EnrichmentRequest.builder().scope(EnrichmentRequest.Scope.BOOK).build());
        context.addAuthorBio(authorName, bio);
        return context;
    }

    private AuthorEntity author(String name) {
        return AuthorEntity.builder().id(3L).name(name).build();
    }

    @Nested
    class WhenTheStoredNameMatchesExactly {

        @Test
        void writesTheBiographyOntoTheAuthor() {
            AuthorEntity stored = author("Верещагин Александр Валериевич");
            when(authorRepository.findByName("Верещагин Александр Валериевич"))
                    .thenReturn(Optional.of(stored));

            applier.apply(contextWithBio("Верещагин Александр Валериевич", "Родился в 1861 году."),
                    EnrichmentOutcome.builder().build());

            assertThat(stored.getDescription()).isEqualTo("Родился в 1861 году.");
            verify(authorRepository).save(stored);
        }

        @Test
        void neverScansTheAuthorTableToFindIt() {
            when(authorRepository.findByName(anyString()))
                    .thenReturn(Optional.of(author("Верещагин Александр Валериевич")));

            applier.apply(contextWithBio("Верещагин Александр Валериевич", "Родился в 1861 году."),
                    EnrichmentOutcome.builder().build());

            verify(authorRepository, never()).findByNameIgnoreCase(any());
        }
    }

    @Nested
    class WhenOnlyTheCaseDiffers {

        @Test
        void stillFindsTheAuthorAndWritesTheBiography() {
            AuthorEntity stored = author("GEORGE ORWELL");
            when(authorRepository.findByName("George Orwell")).thenReturn(Optional.empty());
            when(authorRepository.findByNameIgnoreCase("George Orwell")).thenReturn(Optional.of(stored));

            applier.apply(contextWithBio("George Orwell", "Born in 1903."),
                    EnrichmentOutcome.builder().build());

            assertThat(stored.getDescription()).isEqualTo("Born in 1903.");
            verify(authorRepository).save(stored);
        }
    }

    @Nested
    class WhenTheAuthorIsNotStoredAtAll {

        @Test
        void writesNothing() {
            when(authorRepository.findByName(anyString())).thenReturn(Optional.empty());
            when(authorRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());

            applier.apply(contextWithBio("Nobody At All", "A life."),
                    EnrichmentOutcome.builder().build());

            verify(authorRepository, never()).save(any());
        }
    }

    @Nested
    class WhatItRefusesToOverwrite {

        @Test
        void leavesALockedDescriptionAlone() {
            AuthorEntity stored = AuthorEntity.builder()
                    .id(3L).name("George Orwell").description("Mine.").descriptionLocked(true).build();
            when(authorRepository.findByName("George Orwell")).thenReturn(Optional.of(stored));

            applier.apply(contextWithBio("George Orwell", "Born in 1903."),
                    EnrichmentOutcome.builder().build());

            assertThat(stored.getDescription()).isEqualTo("Mine.");
            verify(authorRepository, never()).save(any());
        }

        @Test
        void leavesADescriptionThatIsAlreadyThere() {
            AuthorEntity stored = AuthorEntity.builder()
                    .id(3L).name("George Orwell").description("Already written.").build();
            when(authorRepository.findByName("George Orwell")).thenReturn(Optional.of(stored));

            applier.apply(contextWithBio("George Orwell", "Born in 1903."),
                    EnrichmentOutcome.builder().build());

            assertThat(stored.getDescription()).isEqualTo("Already written.");
            verify(authorRepository, never()).save(any());
        }
    }
}
