package org.booklore.service.metadata.smart;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.smart.RatingVerification;
import org.booklore.model.dto.smart.ResolvedWorkIdentity;
import org.booklore.model.dto.smart.SmartEnrichmentEvent;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.parser.GoodReadsParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

/**
 * Runs one enrichment attempt for a single book, on demand.
 * <p>
 * The split it enforces is the point of the whole service: the agent supplies <em>identity</em> —
 * which work this is, and where to read about it — while every number that lands in metadata is
 * fetched by the existing provider parsers. An agent asked for a rating will happily produce a
 * plausible one, and a plausible wrong number is unfalsifiable once stored.
 */
@Slf4j
@Service
public class SmartEnrichmentService {

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final WorkIdentityResolver workIdentityResolver;
    private final GoodReadsParser goodReadsParser;
    private final MetadataProposalBuilder metadataProposalBuilder;
    private final TransactionTemplate readOnlyTransaction;

    public SmartEnrichmentService(BookRepository bookRepository,
                                  BookMapper bookMapper,
                                  WorkIdentityResolver workIdentityResolver,
                                  GoodReadsParser goodReadsParser,
                                  MetadataProposalBuilder metadataProposalBuilder,
                                  PlatformTransactionManager transactionManager) {
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
        this.workIdentityResolver = workIdentityResolver;
        this.goodReadsParser = goodReadsParser;
        this.metadataProposalBuilder = metadataProposalBuilder;
        // Own instance rather than the shared bean: setReadOnly would leak to every other consumer.
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public boolean isAvailable() {
        return workIdentityResolver.isAvailable();
    }

    public Flux<SmartEnrichmentEvent> enrich(long bookId) {
        if (!isAvailable()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Smart enrichment is not enabled on this instance");
        }
        // The mapping touches lazy collections (authors, categories …). Materialise the whole DTO
        // inside a short read-only transaction so it is fully detached before the Flux — which runs
        // later on another thread, long after any session would have closed — ever reads it. The
        // transaction covers only this fast load; the minutes-long agent call happens in the Flux,
        // outside it, holding no connection.
        Book book = readOnlyTransaction.execute(status -> {
            BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId)
                    .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
            return bookMapper.toBook(bookEntity);
        });

        return Flux.<SmartEnrichmentEvent>create(sink -> {
            sink.next(SmartEnrichmentEvent.resolving());

            Optional<ResolvedWorkIdentity> resolved = workIdentityResolver.resolve(book);
            if (resolved.isEmpty()) {
                sink.next(SmartEnrichmentEvent.failed("Could not identify the work behind this file"));
                sink.complete();
                return;
            }
            ResolvedWorkIdentity identity = resolved.get();
            sink.next(SmartEnrichmentEvent.verifying(identity));

            BookMetadata verifiedGoodreads = fetchVerifiedGoodreadsMetadata(book, identity);
            RatingVerification ratingVerification = RatingVerification.of(
                    identity.reportedRating(),
                    verifiedGoodreads == null ? null : verifiedGoodreads.getGoodreadsRating());
            if (!ratingVerification.agrees() && ratingVerification.reported() != null && ratingVerification.verified() != null) {
                log.warn("Book {}: agent reported rating {} but Goodreads returned {}",
                        bookId, ratingVerification.reported(), ratingVerification.verified());
            }

            sink.next(SmartEnrichmentEvent.completed(identity, ratingVerification,
                    metadataProposalBuilder.build(book, identity, verifiedGoodreads)));
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Re-fetches the Goodreads entry the agent pointed at, by id.
     * <p>
     * {@code fetchTopMetadata} falls back to a title search when the id lookup fails, and for a
     * translated edition that search would return some unrelated book — which would then be
     * presented as a verified rating. So the id on the result is checked against the one asked for,
     * and anything else counts as unverified.
     */
    private BookMetadata fetchVerifiedGoodreadsMetadata(Book book, ResolvedWorkIdentity identity) {
        Optional<String> goodreadsId = GoodreadsUrlParser.extractBookId(identity.goodreadsUrl());
        if (goodreadsId.isEmpty()) {
            return null;
        }
        Book lookup = bookForGoodreadsLookup(book, goodreadsId.get());
        try {
            BookMetadata metadata = goodReadsParser.fetchTopMetadata(lookup, FetchMetadataRequest.builder()
                    .bookId(book.getId())
                    .build());
            if (metadata == null || !goodreadsId.get().equals(metadata.getGoodreadsId())) {
                log.warn("Book {}: Goodreads returned a different entry than {}, treating the rating as unverified",
                        book.getId(), goodreadsId.get());
                return null;
            }
            return metadata;
        } catch (Exception e) {
            log.warn("Book {}: could not verify Goodreads id {}: {}", book.getId(), goodreadsId.get(), e.getMessage());
            return null;
        }
    }

    /**
     * The parser reads the id off the book it is given, so verification needs a copy carrying the
     * resolved id — never the stored book, which must not be mutated by a lookup.
     */
    private Book bookForGoodreadsLookup(Book book, String goodreadsId) {
        BookMetadata metadata = BookMetadata.builder()
                .title(book.getMetadata() == null ? book.getTitle() : book.getMetadata().getTitle())
                .goodreadsId(goodreadsId)
                .build();
        return Book.builder()
                .id(book.getId())
                .title(book.getTitle())
                .metadata(metadata)
                .build();
    }

}
