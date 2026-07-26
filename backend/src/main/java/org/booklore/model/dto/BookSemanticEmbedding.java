package org.booklore.model.dto;

public record BookSemanticEmbedding(long bookId, String vectorJson, String contentHash) {
}
