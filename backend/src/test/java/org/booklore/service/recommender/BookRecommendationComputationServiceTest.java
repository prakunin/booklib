package org.booklore.service.recommender;

import org.booklore.model.dto.BookRecommendationLite;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookEmbeddingVectorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookEmbeddingCandidate;
import org.booklore.service.book.BookQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookRecommendationComputationServiceTest {

    @Mock
    private BookSimilarityService similarityService;
    @Mock
    private BookVectorService vectorService;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookQueryService bookQueryService;
    @Mock
    private BookEmbeddingVectorRepository embeddingVectorRepository;
    @Mock
    private BookSemanticEmbeddingService semanticEmbeddingService;

    private BookRecommendationComputationService service;

    @BeforeEach
    void setUp() {
        service = new BookRecommendationComputationService(
                similarityService, vectorService, bookRepository, bookQueryService, embeddingVectorRepository,
                semanticEmbeddingService);
    }

    @Test
    void scansEntityCandidatesInKeysetBatchesAndStoresOnlyTheTopResults() {
        BookEntity target = book(1L, "Target");
        List<BookEntity> firstBatch = new ArrayList<>();
        for (long id = 2; id <= 501; id++) {
            firstBatch.add(book(id, "Book " + id));
        }

        when(bookRepository.findByIdWithMetadata(1L)).thenReturn(Optional.of(target));
        when(bookQueryService.getRecommendationCandidatesAfterId(eq(1L), eq(0L), any(Pageable.class)))
                .thenReturn(firstBatch);
        when(bookQueryService.getRecommendationCandidatesAfterId(eq(1L), eq(501L), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(similarityService.calculateSimilarity(eq(target), any(BookEntity.class)))
                .thenAnswer(invocation -> ((BookEntity) invocation.getArgument(1)).getId().doubleValue());
        when(bookQueryService.findAllWithMetadataByIds(anySet())).thenAnswer(invocation -> {
            Set<Long> ids = invocation.getArgument(0);
            return firstBatch.stream().filter(book -> ids.contains(book.getId())).toList();
        });

        service.computeAndStore(1L, 25);

        verify(bookQueryService).getRecommendationCandidatesAfterId(eq(1L), eq(0L), any(Pageable.class));
        verify(bookQueryService).getRecommendationCandidatesAfterId(eq(1L), eq(501L), any(Pageable.class));
        ArgumentCaptor<Set<BookRecommendationLite>> recommendations = ArgumentCaptor.forClass(Set.class);
        verify(bookQueryService).saveRecommendations(eq(1L), recommendations.capture());
        assertThat(recommendations.getValue()).hasSize(25);
        assertThat(recommendations.getValue()).extracting(BookRecommendationLite::getB).contains(501L, 500L);
    }

    @Test
    void usesAnnCandidatesForStoredEmbeddingAndKeepsExistingReranking() {
        BookEntity target = book(1L, "Target");
        target.getMetadata().setEmbeddingVector("[1.0,0.0]");
        target.getMetadata().setSeriesName("Shared Series");
        BookEntity allowed = book(2L, "Allowed");
        BookEntity sameSeries = book(3L, "Same Series");
        sameSeries.getMetadata().setSeriesName("Shared Series");

        when(bookRepository.findByIdWithMetadata(1L)).thenReturn(Optional.of(target));
        when(vectorService.deserializeVector("[1.0,0.0]")).thenReturn(new double[]{1.0, 0.0});
        when(embeddingVectorRepository.findNearestCandidates(1L, "[1.0,0.0]", 1000, null))
                .thenReturn(List.of(
                        new BookEmbeddingCandidate(3L, 0.99, "shared series"),
                        new BookEmbeddingCandidate(2L, 0.90, null)));
        when(bookQueryService.findAllWithMetadataByIds(Set.of(2L))).thenReturn(List.of(allowed));

        service.computeAndStore(1L, 25);

        verify(embeddingVectorRepository).findNearestCandidates(1L, "[1.0,0.0]", 1000, null);
        verify(bookQueryService, never()).getEmbeddingCandidatesAfterId(anyLong(), anyLong(), any(Pageable.class));
        ArgumentCaptor<Set<BookRecommendationLite>> recommendations = ArgumentCaptor.forClass(Set.class);
        verify(bookQueryService).saveRecommendations(eq(1L), recommendations.capture());
        assertThat(recommendations.getValue())
                .extracting(BookRecommendationLite::getB)
                .containsExactly(2L);
    }

    @Test
    void usesSemanticAnnWhenSemanticModelIsActive() {
        BookEntity target = book(1L, "Цель");
        BookEntity candidate = book(2L, "Кандидат");

        when(bookRepository.findByIdWithMetadata(1L)).thenReturn(Optional.of(target));
        when(embeddingVectorRepository.activeModel()).thenReturn("qwen3-128-v1");
        when(semanticEmbeddingService.modelVersion()).thenReturn("qwen3-128-v1");
        when(semanticEmbeddingService.ensureEmbedding(target)).thenReturn(Optional.of("[0.1,0.2]"));
        when(embeddingVectorRepository.findNearestCandidates(1L, "[0.1,0.2]", 1000, "qwen3-128-v1"))
                .thenReturn(List.of(new BookEmbeddingCandidate(2L, 0.91, null)));
        when(bookQueryService.findAllWithMetadataByIds(Set.of(2L))).thenReturn(List.of(candidate));

        service.computeAndStore(1L, 25);

        verify(embeddingVectorRepository).findNearestCandidates(1L, "[0.1,0.2]", 1000, "qwen3-128-v1");
        verify(vectorService, never()).deserializeVector(anyString());
        verify(bookQueryService).saveRecommendations(eq(1L), anySet());
    }

    private BookEntity book(long id, String title) {
        BookMetadataEntity metadata = BookMetadataEntity.builder().bookId(id).title(title).build();
        return BookEntity.builder().id(id).metadata(metadata).build();
    }
}
