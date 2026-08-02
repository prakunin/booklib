package org.booklore.service.metadata.smart;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.smart.MetadataFieldProposal;
import org.booklore.model.dto.smart.ResolvedWorkIdentity;
import org.booklore.model.dto.smart.SmartEnrichmentEvent;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.parser.GoodReadsParser;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmartEnrichmentServiceTest {

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final BookMapper bookMapper = mock(BookMapper.class);
    private final WorkIdentityResolver resolver = mock(WorkIdentityResolver.class);
    private final GoodReadsParser goodReadsParser = mock(GoodReadsParser.class);
    // A bare mock is enough: TransactionTemplate.execute runs the callback and passes the (null)
    // status straight through to commit, which the mock no-ops. The callback ignores the status.
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private final SmartEnrichmentService service =
            new SmartEnrichmentService(bookRepository, bookMapper, resolver, goodReadsParser,
                    new MetadataProposalBuilder(), transactionManager);

    private final BookEntity bookEntity = new BookEntity();

    @BeforeEach
    void setUp() {
        when(resolver.isAvailable()).thenReturn(true);
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));
        when(bookMapper.toBook(bookEntity)).thenReturn(storedBook());
    }

    private Book storedBook() {
        return Book.builder()
                .id(1L)
                .title("Путевой дневник")
                .metadata(BookMetadata.builder()
                        .title("Путевой дневник. Путешествие Мишеля де Монтеня в Германию и Италию")
                        .authors(List.of("Монтень Мишель"))
                        .language("ru")
                        .build())
                .build();
    }

    private ResolvedWorkIdentity identity(Double reportedRating) {
        return TestIdentities.identity(reportedRating);
    }

    private BookMetadata goodreadsResult(String goodreadsId, Double rating) {
        return BookMetadata.builder().goodreadsId(goodreadsId).goodreadsRating(rating).build();
    }

    private SmartEnrichmentEvent finalEvent() {
        List<SmartEnrichmentEvent> events = service.enrich(1L).collectList().block();
        assertThat(events).isNotNull().isNotEmpty();
        return events.getLast();
    }

    @Nested
    class RatingVerification {

        // The whole reason the agent is not allowed to supply numbers: only what the parser fetches
        // by id may be proposed.
        @Test
        void proposesTheRatingFetchedByTheParserRatherThanTheOneTheAgentReported() {
            when(resolver.resolve(any())).thenReturn(Optional.of(identity(3.99)));
            when(goodReadsParser.fetchTopMetadata(any(), any())).thenReturn(goodreadsResult("104595", 3.68));

            SmartEnrichmentEvent event = finalEvent();

            assertThat(event.stage()).isEqualTo(SmartEnrichmentEvent.Stage.COMPLETED);
            assertThat(event.ratingVerification().reported()).isEqualTo(3.99);
            assertThat(event.ratingVerification().verified()).isEqualTo(3.68);
            assertThat(event.ratingVerification().agrees()).isFalse();
            assertThat(proposalFor(event, "goodreadsRating").proposedValue()).isEqualTo("3.68");
        }

        @Test
        void marksAgreementWhenBothNumbersMatch() {
            when(resolver.resolve(any())).thenReturn(Optional.of(identity(3.68)));
            when(goodReadsParser.fetchTopMetadata(any(), any())).thenReturn(goodreadsResult("104595", 3.68));

            assertThat(finalEvent().ratingVerification().agrees()).isTrue();
        }

        // fetchTopMetadata silently falls back to a title search when the id lookup fails. For a
        // Russian title that search returns something unrelated, and presenting its rating as
        // verified would be worse than presenting nothing.
        @Test
        void refusesARatingFromADifferentGoodreadsEntry() {
            when(resolver.resolve(any())).thenReturn(Optional.of(identity(3.68)));
            when(goodReadsParser.fetchTopMetadata(any(), any())).thenReturn(goodreadsResult("999999", 4.5));

            SmartEnrichmentEvent event = finalEvent();

            assertThat(event.ratingVerification().verified()).isNull();
            assertThat(event.proposals()).noneMatch(p -> p.field().equals("goodreadsRating"));
        }

        @Test
        void survivesAParserFailure() {
            when(resolver.resolve(any())).thenReturn(Optional.of(identity(3.68)));
            when(goodReadsParser.fetchTopMetadata(any(), any())).thenThrow(new IllegalStateException("goodreads down"));

            SmartEnrichmentEvent event = finalEvent();

            assertThat(event.stage()).isEqualTo(SmartEnrichmentEvent.Stage.COMPLETED);
            assertThat(event.ratingVerification().verified()).isNull();
            assertThat(proposalFor(event, "description")).isNotNull();
        }

        @Test
        void skipsVerificationWhenNoGoodreadsUrlWasResolved() {
            ResolvedWorkIdentity withoutUrl = TestIdentities.builder()
                    .goodreadsUrl(null)
                    .description("Аннотация.")
                    .descriptionSourceUrl(null)
                    .sources(List.of())
                    .build();
            when(resolver.resolve(any())).thenReturn(Optional.of(withoutUrl));

            SmartEnrichmentEvent event = finalEvent();

            verify(goodReadsParser, never()).fetchTopMetadata(any(), any());
            assertThat(event.proposals()).extracting(MetadataFieldProposal::field).containsExactly("description");
        }
    }

    @Nested
    class Proposals {

        @Test
        void carryTheCurrentValueAndTheSourceUrl() {
            when(resolver.resolve(any())).thenReturn(Optional.of(identity(3.68)));
            when(goodReadsParser.fetchTopMetadata(any(), any())).thenReturn(goodreadsResult("104595", 3.68));

            MetadataFieldProposal description = proposalFor(finalEvent(), "description");

            assertThat(description.currentValue()).isNull();
            assertThat(description.proposedValue()).isEqualTo("Дословная аннотация издателя.");
            assertThat(description.sourceUrl()).isEqualTo("https://www.labirint.ru/books/700000/");
            assertThat(description.locked()).isFalse();
        }

        @Test
        void flagLockedFieldsInsteadOfHidingThem() {
            when(bookMapper.toBook(bookEntity)).thenReturn(Book.builder()
                    .id(1L)
                    .metadata(BookMetadata.builder()
                            .description("Ручное описание")
                            .descriptionLocked(true)
                            .build())
                    .build());
            when(resolver.resolve(any())).thenReturn(Optional.of(identity(3.68)));
            when(goodReadsParser.fetchTopMetadata(any(), any())).thenReturn(goodreadsResult("104595", 3.68));

            MetadataFieldProposal description = proposalFor(finalEvent(), "description");

            assertThat(description.locked()).isTrue();
            assertThat(description.currentValue()).isEqualTo("Ручное описание");
        }

        @Test
        void theStoredBookIsNeverMutatedByTheLookup() {
            Book stored = storedBook();
            when(bookMapper.toBook(bookEntity)).thenReturn(stored);
            when(resolver.resolve(any())).thenReturn(Optional.of(identity(3.68)));
            when(goodReadsParser.fetchTopMetadata(any(), any())).thenReturn(goodreadsResult("104595", 3.68));

            finalEvent();

            assertThat(stored.getMetadata().getGoodreadsId()).isNull();
        }
    }

    @Nested
    class Failures {

        @Test
        void reportsFailureWhenTheWorkCannotBeIdentified() {
            when(resolver.resolve(any())).thenReturn(Optional.empty());

            SmartEnrichmentEvent event = finalEvent();

            assertThat(event.stage()).isEqualTo(SmartEnrichmentEvent.Stage.FAILED);
            assertThat(event.proposals()).isEmpty();
        }

        @Test
        void rejectsTheRequestWhenEnrichmentIsDisabled() {
            when(resolver.isAvailable()).thenReturn(false);

            assertThatThrownBy(() -> service.enrich(1L)).hasMessageContaining("not enabled");
        }

        @Test
        void rejectsAnUnknownBook() {
            when(bookRepository.findByIdWithBookFiles(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.enrich(7L)).hasMessageContaining("7");
        }
    }

    @Nested
    class Streaming {

        // The run takes tens of seconds; the progress stages are what the dialog shows meanwhile.
        @Test
        void emitsProgressStagesBeforeTheResult() {
            when(resolver.resolve(any())).thenReturn(Optional.of(identity(3.68)));
            when(goodReadsParser.fetchTopMetadata(any(), any())).thenReturn(goodreadsResult("104595", 3.68));

            List<SmartEnrichmentEvent> events = service.enrich(1L).collectList().block();

            assertThat(events).extracting(SmartEnrichmentEvent::stage).containsExactly(
                    SmartEnrichmentEvent.Stage.RESOLVING,
                    SmartEnrichmentEvent.Stage.VERIFYING,
                    SmartEnrichmentEvent.Stage.COMPLETED);
        }

        @Test
        void looksUpGoodreadsByTheResolvedIdRatherThanBySearchTerms() {
            when(resolver.resolve(any())).thenReturn(Optional.of(identity(3.68)));
            when(goodReadsParser.fetchTopMetadata(any(), any())).thenReturn(goodreadsResult("104595", 3.68));

            service.enrich(1L).collectList().block();

            org.mockito.ArgumentCaptor<Book> bookCaptor = org.mockito.ArgumentCaptor.forClass(Book.class);
            verify(goodReadsParser).fetchTopMetadata(bookCaptor.capture(), any(FetchMetadataRequest.class));
            assertThat(bookCaptor.getValue().getMetadata().getGoodreadsId()).isEqualTo("104595");
        }
    }

    private MetadataFieldProposal proposalFor(SmartEnrichmentEvent event, String field) {
        return event.proposals().stream()
                .filter(proposal -> proposal.field().equals(field))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No proposal for field " + field));
    }
}
