package org.booklore.model.dto.smart;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ResolvedWorkIdentityJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsAgentSnakeCaseButSerializesApiCamelCase() throws Exception {
        ResolvedWorkIdentity identity = objectMapper.readValue("""
                {
                  "original_title": "Практиканты",
                  "original_author": "Анатолий Бурак",
                  "edition_author": "Анатолий Бурак",
                  "first_published_year": 2020
                }
                """, ResolvedWorkIdentity.class);

        assertThat(identity.originalAuthor()).isEqualTo("Анатолий Бурак");

        String apiJson = objectMapper.writeValueAsString(identity);
        assertThat(apiJson)
                .contains("\"originalTitle\":\"Практиканты\"")
                .contains("\"originalAuthor\":\"Анатолий Бурак\"")
                .contains("\"editionAuthor\":\"Анатолий Бурак\"")
                .contains("\"firstPublishedYear\":2020")
                .doesNotContain("original_title", "original_author", "edition_author", "first_published_year");
    }
}
