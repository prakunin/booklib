package org.booklore.service.recommender;

import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.BookSemanticEmbedding;
import org.booklore.model.dto.PreparedBookEmbedding;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookEmbeddingVectorRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookSemanticEmbeddingService {

    private final BookVectorService vectorService;
    private final OllamaEmbeddingClient embeddingClient;
    private final BookEmbeddingVectorRepository embeddingRepository;

    public Set<Long> updateEmbeddings(List<BookEntity> books) {
        if (books.isEmpty()) {
            return Set.of();
        }

        Map<Long, PreparedBookEmbedding> preparedById = new LinkedHashMap<>();
        for (BookEntity book : books) {
            PreparedBookEmbedding prepared = vectorService.prepareSemanticEmbedding(book);
            preparedById.put(prepared.bookId(), prepared);
        }

        String modelVersion = modelVersion();
        Map<Long, String> storedContentHashes =
                embeddingRepository.findSemanticContentHashes(preparedById.keySet(), modelVersion);
        List<PreparedBookEmbedding> changed = preparedById.values().stream()
                .filter(prepared -> !prepared.contentHash()
                        .equals(storedContentHashes.get(prepared.bookId())))
                .toList();
        if (changed.isEmpty()) {
            return Set.of();
        }

        List<double[]> vectors = embeddingClient.embed(
                changed.stream().map(PreparedBookEmbedding::text).toList());
        List<BookSemanticEmbedding> embeddings = new ArrayList<>(changed.size());
        for (int index = 0; index < changed.size(); index++) {
            PreparedBookEmbedding prepared = changed.get(index);
            String vectorJson = vectorService.serializeVector(vectors.get(index));
            if (vectorJson == null) {
                throw new IllegalStateException("Failed to serialize semantic embedding for book " + prepared.bookId());
            }
            embeddings.add(new BookSemanticEmbedding(
                    prepared.bookId(),
                    vectorJson,
                    prepared.contentHash()));
        }
        embeddingRepository.upsertSemantic(embeddings, modelVersion);
        return changed.stream()
                .map(PreparedBookEmbedding::bookId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Optional<String> ensureEmbedding(BookEntity book) {
        updateEmbeddings(List.of(book));
        return embeddingRepository.findSemanticVectorJson(book.getId(), modelVersion());
    }

    public String modelVersion() {
        return embeddingClient.modelVersion();
    }
}
