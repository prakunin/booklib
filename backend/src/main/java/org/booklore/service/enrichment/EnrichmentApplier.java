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
import org.booklore.service.metadata.MetadataRefreshService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
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
            List<AuthorEntity> authors = authorRepository.findByNameIgnoreCase(name).stream().toList();
            if (authors.isEmpty()) {
                return;
            }
            AuthorEntity author = authors.getFirst();
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
}
