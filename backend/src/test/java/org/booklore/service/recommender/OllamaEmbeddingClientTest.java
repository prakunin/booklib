package org.booklore.service.recommender;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.RecommendationEmbeddingSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaEmbeddingClientTest {

    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private HttpServer server;
    private OllamaEmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        RecommendationEmbeddingSettings settings = RecommendationEmbeddingSettings.builder()
                .ollamaBaseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .model("embeddinggemma")
                .dimensions(2)
                .batchSize(2)
                .build();
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder()
                .recommendationEmbeddingSettings(settings)
                .build());
        client = new OllamaEmbeddingClient(appSettingService);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void embedsInConfiguredBatchesAndEmbedsQueries() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/embed", exchange -> {
            int call = calls.getAndIncrement();
            respond(exchange, call == 0
                    ? "{\"embeddings\":[[1.0,2.0],[3.0,4.0]]}"
                    : "{\"embeddings\":[[5.0,6.0]]}");
        });

        List<double[]> embeddings = client.embed(List.of("one", "two", "three"));

        assertThat(embeddings).hasSize(3);
        assertThat(embeddings.getFirst()).containsExactly(1.0, 2.0);
        assertThat(client.embedQuery("query")).containsExactly(5.0, 6.0);
        assertThat(client.embed(List.of())).isEmpty();
        assertThat(client.modelVersion()).isEqualTo("embeddinggemma-2-v2");
    }

    @Test
    void listsSortedNonNullModelsAndHandlesMissingPayload() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/tags", exchange -> respond(exchange, calls.getAndIncrement() == 0
                ? "{\"models\":[{\"name\":\"zeta\"},{\"name\":null},{\"name\":\"alpha\"}]}"
                : "{}"));

        assertThat(client.listModels()).containsExactly("alpha", "zeta");
        assertThat(client.listModels()).isEmpty();
    }

    @Test
    void rejectsWrongEmbeddingCountAndDimensions() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/embed", exchange -> respond(exchange, calls.getAndIncrement() == 0
                ? "{\"embeddings\":[]}"
                : "{\"embeddings\":[[1.0]]}"));
        List<String> text = List.of("one");

        assertThatThrownBy(() -> client.embed(text))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("count");
        assertThatThrownBy(() -> client.embed(text))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension");
    }

    private void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
