package org.booklore.service.enrichment.catalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads one reviews document of the Flibusta catalog:
 *
 * <pre>{@code
 * [ { "name": "", "text": "…<br/>…", "time": "2007-10-22 13:06:15" } ]
 * }</pre>
 * <p>
 * Timestamps carry no zone. They are read as UTC — the alternative, guessing the catalog's local
 * zone, would silently shift every review by a few hours with no way to tell that it happened.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlibustaReviewParser {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;

    public List<CatalogReview> parse(byte[] json) {
        if (json == null || json.length == 0) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<CatalogReview> reviews = new ArrayList<>();
            for (JsonNode node : root) {
                toReview(node).ifPresent(reviews::add);
            }
            return reviews;
        } catch (Exception e) {
            log.warn("Could not parse reviews document ({} bytes): {}", json.length, e.getMessage());
            return List.of();
        }
    }

    private Optional<CatalogReview> toReview(JsonNode node) {
        String body = node.path("text").asString(null);
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        String reviewerName = node.path("name").asString(null);
        return Optional.of(new CatalogReview(
                reviewerName == null || reviewerName.isBlank() ? null : reviewerName.strip(),
                body.strip(),
                parseTime(node.path("time").asString(null))));
    }

    private Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip(), TIME_FORMAT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            log.debug("Unparseable review timestamp '{}'", value);
            return null;
        }
    }
}
