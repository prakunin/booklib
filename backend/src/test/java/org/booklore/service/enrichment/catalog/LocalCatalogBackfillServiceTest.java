package org.booklore.service.enrichment.catalog;

import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.EnrichmentWritePolicy;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.enrichment.EnrichmentPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalCatalogBackfillServiceTest {

    private final BookFileRepository bookFileRepository = mock(BookFileRepository.class);
    private final EnrichmentPipeline pipeline = mock(EnrichmentPipeline.class);
    private final LocalCatalogIndexService indexService = mock(LocalCatalogIndexService.class);

    private final LocalCatalogBackfillService service =
            new LocalCatalogBackfillService(bookFileRepository, pipeline, indexService);

    private Object[] row(long id, String archive, String entry) {
        return new Object[]{id, archive, entry};
    }

    @Test
    void enrichesEveryArchivedBookAndStops() {
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq(""), eq(""), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(row(1L, "a.zip", "1.fb2"), row(2L, "a.zip", "2.fb2")));
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq("a.zip"), eq("2.fb2"), eq(2L), any(Pageable.class)))
                .thenReturn(List.of());

        var result = service.run(7L, "task-1", () -> false, progress -> { });

        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.cancelled()).isFalse();
        verify(pipeline).enrich(eq(1L), any(EnrichmentRequest.class));
        verify(pipeline).enrich(eq(2L), any(EnrichmentRequest.class));
    }

    @Test
    void indexesBeforeWalkingBooks() {
        when(bookFileRepository.findArchivedBooksForBackfill(anyLong(), any(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        service.run(7L, "task-1", () -> false, progress -> { });

        verify(indexService).ensureIndexed(7L);
    }

    @Test
    void pinsLocalStepsAndAutoIfEmpty() {
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq(""), eq(""), eq(0L), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(row(1L, "a.zip", "1.fb2")));
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq("a.zip"), eq("1.fb2"), eq(1L), any(Pageable.class)))
                .thenReturn(List.of());

        service.run(7L, "task-1", () -> false, progress -> { });

        ArgumentCaptor<EnrichmentRequest> request = ArgumentCaptor.captor();
        verify(pipeline).enrich(eq(1L), request.capture());
        assertThat(request.getValue().getWritePolicy()).isEqualTo(EnrichmentWritePolicy.AUTO_IF_EMPTY);
        assertThat(request.getValue().isAgentAllowed()).isFalse();
        assertThat(request.getValue().getSteps()).containsExactlyInAnyOrder(
                EnrichmentStepType.LOCAL_CATALOG,
                EnrichmentStepType.LOCAL_LANGUAGE,
                EnrichmentStepType.LOCAL_COMPILATION,
                EnrichmentStepType.REVIEWS,
                EnrichmentStepType.AUTHOR_BIO);
    }

    @Test
    void stopsWhenCancelled() {
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq(""), eq(""), eq(0L), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(row(1L, "a.zip", "1.fb2")));
        AtomicBoolean cancelled = new AtomicBoolean(true);

        var result = service.run(7L, "task-1", cancelled::get, progress -> { });

        assertThat(result.cancelled()).isTrue();
        verify(pipeline, never()).enrich(anyLong(), any(EnrichmentRequest.class));
    }

    @Test
    void countsAFailedBookAndKeepsGoing() {
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq(""), eq(""), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(row(1L, "a.zip", "1.fb2"), row(2L, "a.zip", "2.fb2")));
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq("a.zip"), eq("2.fb2"), eq(2L), any(Pageable.class)))
                .thenReturn(List.of());
        when(pipeline.enrich(eq(1L), any(EnrichmentRequest.class)))
                .thenThrow(new IllegalStateException("boom"));

        var result = service.run(7L, "task-1", () -> false, progress -> { });

        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        verify(pipeline).enrich(eq(2L), any(EnrichmentRequest.class));
    }

    @Test
    void doesNotSkipATiedArchiveAndEntryGroupSplitAcrossAPageBoundary() {
        // Page 1 ends mid-tie: rows 1 and 2 share the same (archive, entry), and the tie group has a
        // third member, row 3, that the LIMIT boundary cut off. Without the book id as a third key in
        // the cursor, the next page's strict ">" on (archive, entry) alone would permanently exclude
        // row 3 — a silent skip, since nothing here looks like an error.
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq(""), eq(""), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(row(1L, "a.zip", "5.fb2"), row(2L, "a.zip", "5.fb2")));
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq("a.zip"), eq("5.fb2"), eq(2L), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(row(3L, "a.zip", "5.fb2")));
        when(bookFileRepository.findArchivedBooksForBackfill(eq(7L), eq("a.zip"), eq("5.fb2"), eq(3L), any(Pageable.class)))
                .thenReturn(List.of());

        var result = service.run(7L, "task-1", () -> false, progress -> { });

        assertThat(result.processed()).isEqualTo(3);
        assertThat(result.failed()).isZero();
        verify(pipeline).enrich(eq(1L), any(EnrichmentRequest.class));
        verify(pipeline).enrich(eq(2L), any(EnrichmentRequest.class));
        verify(pipeline).enrich(eq(3L), any(EnrichmentRequest.class));
    }
}
