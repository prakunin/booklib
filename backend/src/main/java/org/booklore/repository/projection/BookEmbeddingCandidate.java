package org.booklore.repository.projection;

public record BookEmbeddingCandidate(Long bookId, double score, String seriesName) {
}
