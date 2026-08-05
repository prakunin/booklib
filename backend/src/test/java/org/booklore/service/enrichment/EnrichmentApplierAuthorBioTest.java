package org.booklore.service.enrichment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.MetadataFetchJobRepository;
import org.booklore.service.metadata.MetadataProposalProvenanceService;
import org.booklore.service.metadata.MetadataRefreshService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
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
    @Mock
    private MetadataProposalProvenanceService proposalProvenanceService;

    private EnrichmentApplier applier;

    @BeforeEach
    void setUp() {
        applier = new EnrichmentApplier(bookRepository, authorRepository, jobRepository,
                metadataRefreshService, new ObjectMapper(), proposalProvenanceService);
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

            verify(authorRepository, never()).findAllByNameIgnoreCaseOrderByIdAsc(any());
        }
    }

    @Nested
    class WhenOnlyTheCaseDiffers {

        @Test
        void stillFindsTheAuthorAndWritesTheBiography() {
            AuthorEntity stored = author("GEORGE ORWELL");
            when(authorRepository.findByName("George Orwell")).thenReturn(Optional.empty());
            when(authorRepository.findAllByNameIgnoreCaseOrderByIdAsc("George Orwell"))
                    .thenReturn(List.of(stored));

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
            when(authorRepository.findAllByNameIgnoreCaseOrderByIdAsc(anyString())).thenReturn(List.of());

            applier.apply(contextWithBio("Nobody At All", "A life."),
                    EnrichmentOutcome.builder().build());

            verify(authorRepository, never()).save(any());
        }
    }

    /**
     * {@code unique_name} only constrains the exact string, so on a collation that is not
     * case-insensitive two rows can legitimately share a case-folded name (e.g. {@code Orwell} and
     * {@code ORWELL}). Before this fix, the fallback used {@code findByNameIgnoreCase}, which is
     * {@code Optional}-returning and therefore throws {@code IncorrectResultSizeDataAccessException}
     * the instant more than one row matches — and because {@link EnrichmentApplier#apply} is
     * {@code @Transactional}, that exception loses the whole book's enrichment over one ambiguous
     * author name, exactly the failure mode Part B of this task exists to eliminate elsewhere.
     */
    @Nested
    class WhenTheNameIsAmbiguousCaseInsensitively {

        private Logger logger;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attachAppender() {
            logger = (Logger) LoggerFactory.getLogger(EnrichmentApplier.class);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
            appender.stop();
        }

        @Test
        void writesToTheLowestIdInsteadOfAbortingTheBooksEnrichment() {
            AuthorEntity lowerId = author("Orwell");
            AuthorEntity higherId = AuthorEntity.builder().id(7L).name("ORWELL").build();
            when(authorRepository.findByName("Orwell")).thenReturn(Optional.empty());
            when(authorRepository.findAllByNameIgnoreCaseOrderByIdAsc("Orwell"))
                    .thenReturn(List.of(lowerId, higherId));

            applier.apply(contextWithBio("Orwell", "Born in 1903."), EnrichmentOutcome.builder().build());

            assertThat(lowerId.getDescription()).isEqualTo("Born in 1903.");
            assertThat(higherId.getDescription()).isNull();
            verify(authorRepository).save(lowerId);
        }

        @Test
        void warnsAboutTheAmbiguityRatherThanResolvingItSilently() {
            when(authorRepository.findByName("Orwell")).thenReturn(Optional.empty());
            when(authorRepository.findAllByNameIgnoreCaseOrderByIdAsc("Orwell"))
                    .thenReturn(List.of(author("Orwell"), AuthorEntity.builder().id(7L).name("ORWELL").build()));

            applier.apply(contextWithBio("Orwell", "Born in 1903."), EnrichmentOutcome.builder().build());

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("Orwell");
            });
        }

        @Test
        void staysQuietWhenExactlyOneAuthorMatches() {
            AuthorEntity stored = author("Orwell");
            when(authorRepository.findByName("Orwell")).thenReturn(Optional.empty());
            when(authorRepository.findAllByNameIgnoreCaseOrderByIdAsc("Orwell")).thenReturn(List.of(stored));

            applier.apply(contextWithBio("Orwell", "Born in 1903."), EnrichmentOutcome.builder().build());

            assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.WARN)).isEmpty();
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
