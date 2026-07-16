package org.booklore.service.recommender;

import org.booklore.model.dto.BookRecommendationLite;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookRepository;
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

    private BookRecommendationComputationService service;

    @BeforeEach
    void setUp() {
        service = new BookRecommendationComputationService(
                similarityService, vectorService, bookRepository, bookQueryService);
    }

    @Test
    void scansEntityCandidatesInKeysetBatchesAndStoresOnlyTheTopResults() {
        BookEntity target = book(1L, "Target");
        List<BookEntity> firstBatch = new ArrayList<>();
        for (long id = 2; id <= 501; id++) {
            firstBatch.add(book(id, "Book " + id));
        }

        when(bookRepository.findByIdWithMetadata(1L)).thenReturn(Optional.of(target));
        when(vectorService.deserializeVector(null)).thenReturn(null);
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

    private BookEntity book(long id, String title) {
        BookMetadataEntity metadata = BookMetadataEntity.builder().bookId(id).title(title).build();
        return BookEntity.builder().id(id).metadata(metadata).build();
    }
}
