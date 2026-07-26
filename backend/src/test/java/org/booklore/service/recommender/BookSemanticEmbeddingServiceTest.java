package org.booklore.service.recommender;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookEmbeddingVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookSemanticEmbeddingServiceTest {

    @Mock
    private BookVectorService vectorService;
    @Mock
    private OllamaEmbeddingClient embeddingClient;
    @Mock
    private BookEmbeddingVectorRepository embeddingRepository;

    private BookSemanticEmbeddingService service;

    @BeforeEach
    void setUp() {
        service = new BookSemanticEmbeddingService(vectorService, embeddingClient, embeddingRepository);
        when(embeddingClient.modelVersion()).thenReturn("qwen3-128-v1");
    }

    @Test
    void embedsOnlyMissingOrChangedBooks() {
        BookEntity unchanged = book(1L);
        BookEntity changed = book(2L);
        when(vectorService.prepareSemanticEmbedding(unchanged))
                .thenReturn(new org.booklore.model.dto.PreparedBookEmbedding(1L, "unchanged", "hash-1"));
        when(vectorService.prepareSemanticEmbedding(changed))
                .thenReturn(new org.booklore.model.dto.PreparedBookEmbedding(2L, "changed", "hash-2"));
        when(embeddingRepository.findSemanticContentHashes(Set.of(1L, 2L), "qwen3-128-v1"))
                .thenReturn(Map.of(1L, "hash-1", 2L, "old-hash"));
        when(embeddingClient.embed(List.of("changed"))).thenReturn(List.of(new double[]{0.1, 0.2}));
        when(vectorService.serializeVector(any(double[].class))).thenReturn("[0.1,0.2]");

        Set<Long> changedIds = service.updateEmbeddings(List.of(unchanged, changed));

        assertThat(changedIds).containsExactly(2L);
        ArgumentCaptor<List<org.booklore.model.dto.BookSemanticEmbedding>> embeddings =
                ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).upsertSemantic(embeddings.capture(), eq("qwen3-128-v1"));
        assertThat(embeddings.getValue()).singleElement().satisfies(embedding -> {
            assertThat(embedding.bookId()).isEqualTo(2L);
            assertThat(embedding.contentHash()).isEqualTo("hash-2");
            assertThat(embedding.vectorJson()).isEqualTo("[0.1,0.2]");
        });
    }

    @Test
    void skipsOllamaWhenContentAndModelAreCurrent() {
        BookEntity book = book(1L);
        when(vectorService.prepareSemanticEmbedding(book))
                .thenReturn(new org.booklore.model.dto.PreparedBookEmbedding(1L, "text", "hash"));
        when(embeddingRepository.findSemanticContentHashes(Set.of(1L), "qwen3-128-v1"))
                .thenReturn(Map.of(1L, "hash"));

        assertThat(service.updateEmbeddings(List.of(book))).isEmpty();

        verify(embeddingClient, never()).embed(anyList());
        verify(embeddingRepository, never()).upsertSemantic(anyList(), eq("qwen3-128-v1"));
    }

    private BookEntity book(long id) {
        return BookEntity.builder()
                .id(id)
                .metadata(BookMetadataEntity.builder().bookId(id).title("Book " + id).build())
                .build();
    }
}
