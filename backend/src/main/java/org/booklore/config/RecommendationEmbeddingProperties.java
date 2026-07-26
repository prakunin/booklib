package org.booklore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "recommendations.embedding")
public class RecommendationEmbeddingProperties {

    private String ollamaBaseUrl = "http://host.docker.internal:11434";
    private String model = "embeddinggemma:300m";
    private int dimensions = 512;
    private int batchSize = 64;
    /**
     * Measured against the real catalog with embeddinggemma:300m at 512 dimensions: relevant hits score
     * roughly 0.42-0.51 depending on the query, so anything higher silently drops correct answers.
     * Tunable per instance from the settings screen.
     */
    private double minSearchSimilarity = 0.42;
}
