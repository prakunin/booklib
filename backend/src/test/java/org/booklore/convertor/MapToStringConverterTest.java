package org.booklore.convertor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MapToStringConverterTest {

    private MapToStringConverter converter;

    @BeforeEach
    void setUp() {
        converter = new MapToStringConverter(new ObjectMapper());
    }

    @Test
    void convertToDatabaseColumn_shouldSerializeMapToJsonString() {
        Map<String, Object> input = Map.of(
                "title", "Test Book",
                "author", "Test Author",
                "year", 2023
        );

        String result = converter.convertToDatabaseColumn(input);
        assertNotNull(result);
        assertTrue(result.contains("\"title\":\"Test Book\""));
        assertTrue(result.contains("\"author\":\"Test Author\""));
        assertTrue(result.contains("\"year\":2023"));
    }

    @Test
    void convertToDatabaseColumn_withNull_shouldReturnNull() {
        String result = converter.convertToDatabaseColumn(null);

        assertNull(result);
    }

    @Test
    void convertToEntityAttribute_shouldDeserializeJsonStringToMap() {
        String json = "{\"title\":\"Test Book\",\"author\":\"Test Author\",\"year\":2023}";
        Map<String, Object> expected = Map.of(
                "title", "Test Book",
                "author", "Test Author",
                "year", 2023
        );

        Map<String, Object> result = converter.convertToEntityAttribute(json);

        assertNotNull(result);
        assertEquals(expected, result);
    }

    @ParameterizedTest(name = "convertToEntityAttribute with [{0}] returns null")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void convertToEntityAttribute_withNullOrBlankInput_shouldReturnNull(String input) {
        Map<String, Object> result = converter.convertToEntityAttribute(input);

        assertNull(result);
    }
}
