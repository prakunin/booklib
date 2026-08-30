package org.booklore.service.migration.migrations;

import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.EnrichmentWritePolicy;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.enrichment.queue.EnrichmentPriority;
import org.booklore.service.enrichment.queue.EnrichmentQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepairFb2TitlePageArtifactsMigrationTest {

    private static final String MOJIBAKE_TITLE = "�".repeat(12) + " " + "�".repeat(10);

    @Mock
    private BookMetadataRepository bookMetadataRepository;

    @Mock
    private EnrichmentQueueService enrichmentQueueService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private RepairFb2TitlePageArtifactsMigration migration;

    @BeforeEach
    void setUp() {
        // The template only exists to keep the batch's entities managed; run the callback inline.
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(bookMetadataRepository.findUnlockedSubtitlesAfterBookId(anyLong(), any()))
                .thenReturn(List.of());
        when(bookMetadataRepository.findTitlesContainingAfterBookId(anyLong(), any(), any()))
                .thenReturn(List.of());
        migration = new RepairFb2TitlePageArtifactsMigration(
                bookMetadataRepository, enrichmentQueueService, transactionTemplate);
    }

    private BookMetadataEntity metadata(long bookId, String subtitle) {
        return BookMetadataEntity.builder().bookId(bookId).subtitle(subtitle).build();
    }

    @Nested
    class Subtitles {

        @Test
        void clearsTheOnesTheTightenedHeuristicNoLongerProduces() {
            BookMetadataEntity isbn = metadata(1L, "ISBN 5-7281-0149-6");
            BookMetadataEntity boilerplate = metadata(2L, "© Электронная версия книги подготовлена ЛитРес (www.litres.ru), 2014");
            BookMetadataEntity original = metadata(3L, "Laurell K. Hamilton. «Affliction», 2013");
            when(bookMetadataRepository.findUnlockedSubtitlesAfterBookId(eq(0L), any(Pageable.class)))
                    .thenReturn(List.of(isbn, boilerplate, original));

            migration.execute();

            assertThat(isbn.getSubtitle()).isNull();
            assertThat(boilerplate.getSubtitle()).isNull();
            assertThat(original.getSubtitle()).isEqualTo("Laurell K. Hamilton. «Affliction», 2013");
        }

        @Test
        void pagesByBookIdUntilTheRowsRunOut() {
            when(bookMetadataRepository.findUnlockedSubtitlesAfterBookId(eq(0L), any(Pageable.class)))
                    .thenReturn(List.of(metadata(7L, "ISBN 1-2-3")));
            when(bookMetadataRepository.findUnlockedSubtitlesAfterBookId(eq(7L), any(Pageable.class)))
                    .thenReturn(List.of(metadata(9L, "ISSN 2409-0069")));

            migration.execute();

            verify(bookMetadataRepository).findUnlockedSubtitlesAfterBookId(eq(9L), any(Pageable.class));
        }
    }

    @Nested
    class UndecodableTitles {

        @Test
        void queueTheAffectedBooksForLocalCatalogRepair() {
            when(bookMetadataRepository.findTitlesContainingAfterBookId(eq(0L), any(), any(Pageable.class)))
                    .thenReturn(List.of(
                            BookMetadataEntity.builder().bookId(1560378L).title(MOJIBAKE_TITLE).build(),
                            BookMetadataEntity.builder().bookId(11L).title("Café au lait�").build()));

            migration.execute();

            ArgumentCaptor<EnrichmentRequest> request = ArgumentCaptor.forClass(EnrichmentRequest.class);
            verify(enrichmentQueueService).enqueue(request.capture(), eq(EnrichmentPriority.IMPORT_TOP_UP));
            // Only the destroyed title: one stray replacement character is a blemish, not mojibake.
            assertThat(request.getValue().getBookIds()).containsExactly(1560378L);
            assertThat(request.getValue().getSteps()).contains(EnrichmentStepType.LOCAL_CATALOG);
            assertThat(request.getValue().getWritePolicy()).isEqualTo(EnrichmentWritePolicy.AUTO);
            assertThat(request.getValue().isAgentAllowed()).isFalse();
        }

        @Test
        void queueNothingWhenEveryTitleIsReadable() {
            migration.execute();

            verify(enrichmentQueueService, never()).enqueue(any(), anyInt());
        }
    }
}
