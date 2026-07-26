package org.booklore.model.dto.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationEmbeddingSettings {

    private String ollamaBaseUrl;
    private String model;
    private int dimensions;
    private int batchSize;
    /**
     * Minimum cosine similarity a semantic search hit must reach to be returned.
     * Null on settings rows written before semantic search existed; callers fall back to the
     * configured default in that case.
     */
    private Double minSearchSimilarity;
}
