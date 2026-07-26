package org.booklore.service.recommender;

import org.booklore.model.dto.settings.RecommendationEmbeddingSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class OllamaEmbeddingClient {

    private static final String PIPELINE_VERSION = "v2";
    /** Batch backfill embeds up to {@code batchSize} books per call and is allowed to be slow. */
    private static final Duration BATCH_READ_TIMEOUT = Duration.ofMinutes(10);
    /** Interactive search embeds a single short query; a user is waiting on the response. */
    private static final Duration QUERY_READ_TIMEOUT = Duration.ofSeconds(5);

    private final AppSettingService appSettingService;

    public OllamaEmbeddingClient(AppSettingService appSettingService) {
        this.appSettingService = appSettingService;
    }

    private RestClient restClient(RecommendationEmbeddingSettings settings, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(settings.getOllamaBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public List<double[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        RecommendationEmbeddingSettings settings = settings();
        List<double[]> result = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, settings.getBatchSize());
        for (int fromIndex = 0; fromIndex < texts.size(); fromIndex += batchSize) {
            int toIndex = Math.min(fromIndex + batchSize, texts.size());
            result.addAll(embedBatch(texts.subList(fromIndex, toIndex), settings, BATCH_READ_TIMEOUT));
        }
        return result;
    }

    public double[] embedQuery(String text) {
        return embedBatch(List.of(text), settings(), QUERY_READ_TIMEOUT).getFirst();
    }

    public String modelVersion() {
        RecommendationEmbeddingSettings settings = settings();
        return settings.getModel() + "-" + settings.getDimensions() + "-" + PIPELINE_VERSION;
    }

    public List<String> listModels() {
        ModelsResponse response = restClient(settings(), QUERY_READ_TIMEOUT).get()
                .uri("/api/tags")
                .retrieve()
                .body(ModelsResponse.class);
        if (response == null || response.models() == null) {
            return List.of();
        }
        return response.models().stream()
                .map(ModelInfo::name)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    private List<double[]> embedBatch(List<String> texts,
                                      RecommendationEmbeddingSettings settings,
                                      Duration readTimeout) {
        EmbedResponse response = restClient(settings, readTimeout).post()
                .uri("/api/embed")
                .body(new EmbedRequest(settings.getModel(), texts, settings.getDimensions(), true))
                .retrieve()
                .body(EmbedResponse.class);
        if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
            throw new IllegalStateException("Ollama returned an unexpected embedding count");
        }
        List<double[]> embeddings = new ArrayList<>(response.embeddings().size());
        for (List<Double> values : response.embeddings()) {
            if (values == null || values.size() != settings.getDimensions()) {
                throw new IllegalStateException("Ollama returned an unexpected embedding dimension");
            }
            embeddings.add(values.stream().mapToDouble(Double::doubleValue).toArray());
        }
        return embeddings;
    }

    private RecommendationEmbeddingSettings settings() {
        return appSettingService.getAppSettings().getRecommendationEmbeddingSettings();
    }

    private record EmbedRequest(String model, List<String> input, int dimensions, boolean truncate) {
    }

    private record EmbedResponse(List<List<Double>> embeddings) {
    }

    private record ModelsResponse(List<ModelInfo> models) {
    }

    private record ModelInfo(String name) {
    }
}
