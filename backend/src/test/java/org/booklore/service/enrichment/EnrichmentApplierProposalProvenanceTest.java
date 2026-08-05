package org.booklore.service.enrichment;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.entity.MetadataFetchJobEntity;
import org.booklore.model.entity.MetadataFetchProposalEntity;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.MetadataFetchJobRepository;
import org.booklore.service.metadata.MetadataProposalProvenanceService;
import org.booklore.service.metadata.MetadataRefreshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins that {@code EnrichmentApplier.storeProposal} actually asks
 * {@link MetadataProposalProvenanceService} for the provider map and carries it onto the proposal it
 * writes.
 * <p>
 * This is a call-site test, not a test of the service: the service has its own suite, and every wire
 * into it was unproven — delete the {@code describeChanges} call here and nothing in the suite went
 * red. That is the same shape as the defect this branch already spent a commit fixing, where a change
 * had two halves and only one was proven by test.
 * <p>
 * Build time is the only moment at which the provider map can be filtered against what the book
 * already held, because the accept arrives later as a separate client PUT with no before-state. So the
 * second argument matters as much as the call: passing null there would make the service store nothing
 * at all.
 */
@ExtendWith(MockitoExtension.class)
class EnrichmentApplierProposalProvenanceTest {

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

    private EnrichmentContext context(BookMetadata existing) {
        return new EnrichmentContext(
                Book.builder().id(11L).metadata(existing).build(), 19L, "f.zip", "1.fb2",
                EnrichmentRequest.builder().scope(EnrichmentRequest.Scope.BOOK).build());
    }

    @Test
    void storesTheProviderMapDescribedForTheProposalItWrites() {
        BookMetadata existing = BookMetadata.builder().bookId(11L).title("Old").build();
        BookMetadata proposed = BookMetadata.builder().bookId(11L).title("New").build();
        when(proposalProvenanceService.describeChanges(proposed, existing))
                .thenReturn("{\"TITLE\":\"FlibustaLocal\"}");

        applier.apply(context(existing), EnrichmentOutcome.builder().bookId(11L).proposed(proposed).build());

        ArgumentCaptor<MetadataFetchJobEntity> job = ArgumentCaptor.captor();
        verify(jobRepository).save(job.capture());
        List<MetadataFetchProposalEntity> proposals = job.getValue().getProposals();
        assertThat(proposals).hasSize(1);
        assertThat(proposals.getFirst().getFieldProvidersJson()).isEqualTo("{\"TITLE\":\"FlibustaLocal\"}");
    }

    /**
     * The before-state has to be the book's own stored metadata. Handing the service a null existing
     * map is not a smaller answer, it is a different one: {@code describeChanges} returns null for a
     * null existing state on purpose ("the previous state could not be read"), so a call site that
     * passed null would silently attribute nothing while still looking wired up.
     */
    @Test
    void describesTheChangesAgainstWhatTheBookAlreadyHeld() {
        BookMetadata existing = BookMetadata.builder().bookId(11L).title("Old").build();
        BookMetadata proposed = BookMetadata.builder().bookId(11L).title("New").build();

        applier.apply(context(existing), EnrichmentOutcome.builder().bookId(11L).proposed(proposed).build());

        verify(proposalProvenanceService).describeChanges(proposed, existing);
    }

    /**
     * A context whose book carries no metadata must not blow up the write — the applier guards the
     * dereference and hands the service a null before-state, which the service then declines to
     * attribute from.
     */
    @Test
    void passesNullBeforeStateRatherThanFailingWhenTheBookHasNoMetadata() {
        BookMetadata proposed = BookMetadata.builder().bookId(11L).title("New").build();

        applier.apply(context(null), EnrichmentOutcome.builder().bookId(11L).proposed(proposed).build());

        verify(proposalProvenanceService).describeChanges(proposed, null);
        verify(jobRepository).save(any());
    }
}
