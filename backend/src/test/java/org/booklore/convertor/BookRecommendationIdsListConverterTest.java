package org.booklore.convertor;

import org.booklore.model.dto.BookRecommendationLite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BookRecommendationIdsListConverterTest {

    private BookRecommendationIdsListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new BookRecommendationIdsListConverter(new ObjectMapper());
    }

    @Test
    void convertToDatabaseColumn_shouldSerializeSetToJsonString() {
        BookRecommendationLite rec1 = new BookRecommendationLite(1L, 0.95);
        BookRecommendationLite rec2 = new BookRecommendationLite(2L, 0.87);

        Set<BookRecommendationLite> input = Set.of(rec1, rec2);

        String result = converter.convertToDatabaseColumn(input);

        assertNotNull(result);
        assertTrue(result.contains("\"b\":1"));
        assertTrue(result.contains("\"s\":0.95"));
        assertTrue(result.contains("\"b\":2"));
        assertTrue(result.contains("\"s\":0.87"));
    }

    @Test
    void convertToDatabaseColumn_withNull_shouldReturnNull() {
        String result = converter.convertToDatabaseColumn(null);

        assertNull(result);
    }

    @Test
    void convertToEntityAttribute_shouldDeserializeJsonStringToSet() {
        String json = "[{\"b\":1,\"s\":0.95},{\"b\":2,\"s\":0.87}]";

        Set<BookRecommendationLite> result = converter.convertToEntityAttribute(json);

        assertNotNull(result);
        assertEquals(2, result.size());

        BookRecommendationLite book1 = result.stream()
                .filter(b -> b.getB() == 1L)
                .findFirst()
                .orElse(null);
        assertNotNull(book1);
        assertEquals(0.95, book1.getS(), 0.001);

        BookRecommendationLite book2 = result.stream()
                .filter(b -> b.getB() == 2L)
                .findFirst()
                .orElse(null);
        assertNotNull(book2);
        assertEquals(0.87, book2.getS(), 0.001);
    }

    @ParameterizedTest(name = "convertToEntityAttribute with [{0}] returns empty set")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void convertToEntityAttribute_withNullOrBlankInput_shouldReturnEmptySet(String input) {
        Set<BookRecommendationLite> result = converter.convertToEntityAttribute(input);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
