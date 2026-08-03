package org.booklore.service.enrichment.catalog;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlibustaReviewParserTest {

    private final FlibustaReviewParser parser = new FlibustaReviewParser(new ObjectMapper());

    private List<CatalogReview> parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsNameBodyAndTimestamp() {
        List<CatalogReview> reviews = parse("""
                [{"name": "Читатель", "text": "Хорошая книга.", "time": "2007-10-22 13:06:15"}]
                """);

        assertThat(reviews).singleElement().satisfies(review -> {
            assertThat(review.reviewerName()).isEqualTo("Читатель");
            assertThat(review.body()).isEqualTo("Хорошая книга.");
            assertThat(review.postedAt()).isEqualTo(Instant.parse("2007-10-22T13:06:15Z"));
        });
    }

    /**
     * Anonymous reviews are the common case in this catalog — the name field is usually an empty
     * string, which must become null rather than an empty reviewer.
     */
    @Test
    void treatsBlankNameAsAbsent() {
        assertThat(parse("""
                [{"name": "", "text": "Аноним.", "time": "2015-05-01 00:00:00"}]
                """))
                .singleElement()
                .extracting(CatalogReview::reviewerName)
                .isNull();
    }

    @Test
    void skipsReviewsWithoutBody() {
        assertThat(parse("""
                [{"name": "A", "text": "", "time": "2015-05-01 00:00:00"},
                 {"name": "B", "text": "Есть текст.", "time": "2015-05-01 00:00:00"}]
                """))
                .singleElement()
                .extracting(CatalogReview::body)
                .isEqualTo("Есть текст.");
    }

    @Test
    void keepsReviewWhenTimestampIsUnusable() {
        assertThat(parse("""
                [{"name": "A", "text": "Без даты.", "time": "не дата"}]
                """))
                .singleElement()
                .satisfies(review -> {
                    assertThat(review.body()).isEqualTo("Без даты.");
                    assertThat(review.postedAt()).isNull();
                });
    }

    @Test
    void returnsEmptyForNonArrayMalformedAndMissingInput() {
        assertThat(parse("{\"text\": \"объект, не массив\"}")).isEmpty();
        assertThat(parse("[{\"text\": ")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse(new byte[0])).isEmpty();
    }
}
