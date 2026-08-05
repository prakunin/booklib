package org.booklore.service.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.MetadataFetchJobEntity;
import org.booklore.model.entity.MetadataFetchProposalEntity;
import org.booklore.model.enums.EnrichmentWritePolicy;
import org.booklore.model.enums.FetchedMetadataProposalStatus;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.MetadataFetchJobRepository;
import org.booklore.service.metadata.MetadataProposalProvenanceService;
import org.booklore.service.metadata.MetadataRefreshService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes what an enrichment run decided, in one short transaction against freshly loaded entities.
 * <p>
 * Separate from the pipeline because the pipeline runs mostly outside a transaction: the entity read
 * before a minutes-long agent call is stale by the time there is anything to write, so the write
 * path reloads rather than reusing it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentApplier {

    private final BookRepository bookRepository;
    private final AuthorRepository authorRepository;
    private final MetadataFetchJobRepository jobRepository;
    private final MetadataRefreshService metadataRefreshService;
    private final ObjectMapper objectMapper;
    private final MetadataProposalProvenanceService proposalProvenanceService;

    @Transactional
    public void apply(EnrichmentContext context, EnrichmentOutcome outcome) {
        if (!outcome.changedAnything() && context.getAuthorBios().isEmpty()) {
            return;
        }
        if (outcome.getApplied() != null) {
            applyMetadata(context, outcome);
        }
        if (outcome.getProposed() != null) {
            storeProposal(context, outcome);
        }
        applyAuthorBios(context);
    }

    private void applyMetadata(EnrichmentContext context, EnrichmentOutcome outcome) {
        BookEntity book = bookRepository.findById(context.bookId()).orElse(null);
        if (book == null) {
            log.warn("Book {} disappeared during enrichment, discarding the result", context.bookId());
            return;
        }
        EnrichmentWritePolicy policy = context.getRequest().getWritePolicy();
        metadataRefreshService.updateBookMetadata(book, outcome.getApplied(), true, true, policy.replaceMode());
    }

    /**
     * Proposals reuse the existing review queue rather than introducing a second one, so a
     * suggestion from enrichment is accepted through exactly the same screen as one from a manual
     * metadata refresh.
     */
    private void storeProposal(EnrichmentContext context, EnrichmentOutcome outcome) {
        try {
            MetadataFetchJobEntity job = MetadataFetchJobEntity.builder()
                    .taskId(UUID.randomUUID().toString())
                    .status(MetadataFetchTaskStatus.COMPLETED)
                    .startedAt(Instant.now())
                    .completedAt(Instant.now())
                    .totalBooksCount(1)
                    .completedBooks(1)
                    .build();
            job.getProposals().add(MetadataFetchProposalEntity.builder()
                    .job(job)
                    .bookId(context.bookId())
                    .metadataJson(objectMapper.writeValueAsString(outcome.getProposed()))
                    .fieldProvidersJson(proposalProvenanceService.describeChanges(
                            outcome.getProposed(), context.getBook() == null ? null : context.getBook().getMetadata()))
                    .status(FetchedMetadataProposalStatus.FETCHED)
                    .fetchedAt(Instant.now())
                    .build());
            jobRepository.save(job);
        } catch (JacksonException e) {
            log.warn("Could not store the enrichment proposal for book {}: {}", context.bookId(), e.getMessage());
        }
    }

    /**
     * Author biographies are not book metadata and never pass through the per-field priority table.
     * They are written only into an empty description, and never over a locked one: a biography is
     * long-form text a user may well have written themselves.
     */
    private void applyAuthorBios(EnrichmentContext context) {
        context.getAuthorBios().forEach((name, bio) -> {
            AuthorEntity author = findAuthor(name).orElse(null);
            if (author == null) {
                return;
            }
            if (author.isDescriptionLocked()) {
                return;
            }
            if (author.getDescription() != null && !author.getDescription().isBlank()) {
                return;
            }
            author.setDescription(bio);
            authorRepository.save(author);
        });
    }

    /**
     * Exact name first, case-folded name only if that misses.
     * <p>
     * The names reaching this method are the ones already stored on the book's metadata, so the
     * exact lookup is the answer for practically all of them — and it is the only one the database
     * can serve from an index. {@code findByNameIgnoreCase} compiles to {@code upper(name) =
     * upper(?)}, and wrapping the column in a function makes the unique key on {@code author.name}
     * unusable: MariaDB falls back to scanning every row. On a 271,250-author library that was
     * measured at 153 ms per biography against 0.13 ms for the indexed form, and it dominated the
     * local-catalog backfill — 147 s of a 224 s span, against 2.4% for reading the catalog archives
     * the biographies come from.
     * <p>
     * The fallback exists because no migration or configuration pins a collation on {@code
     * author.name} — whether plain equality already folds case is a property of the deployment, not
     * something this code can assume. On a deployment where it does not, {@code unique_name} only
     * constrains the exact string, so {@code Orwell} and {@code ORWELL} can legitimately be two rows,
     * and {@link AuthorRepository#findByNameIgnoreCase} — {@code Optional}-returning — throws {@code
     * IncorrectResultSizeDataAccessException} the moment more than one matches. That exception would
     * escape this method's {@code @Transactional} caller and lose the whole book's enrichment over
     * one ambiguous author name, so the fallback instead takes every case-insensitive match as a
     * {@code List} and keeps the one with the lowest id, logging a warning so an ambiguous name is
     * visible rather than resolved silently.
     */
    private Optional<AuthorEntity> findAuthor(String name) {
        return authorRepository.findByName(name)
                .or(() -> findAuthorCaseInsensitive(name));
    }

    private Optional<AuthorEntity> findAuthorCaseInsensitive(String name) {
        List<AuthorEntity> matches = authorRepository.findAllByNameIgnoreCaseOrderByIdAsc(name);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            log.warn("Author name '{}' matches {} authors case-insensitively; the biography goes to author {}",
                    name, matches.size(), matches.get(0).getId());
        }
        return Optional.of(matches.get(0));
    }
}
