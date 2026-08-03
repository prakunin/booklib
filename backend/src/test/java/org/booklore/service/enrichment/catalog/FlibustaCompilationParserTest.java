package org.booklore.service.enrichment.catalog;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlibustaCompilationParserTest {

    private final FlibustaCompilationParser parser = new FlibustaCompilationParser(new ObjectMapper());

    private final Map<FlibustaCompilationParser.CompilationKey, List<CompilationPart>> collected = new LinkedHashMap<>();

    private int parse(String json) {
        return parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), collected::put);
    }

    @Test
    void reportsEachCompilationWithItsParts() {
        int count = parse("""
                [{"file": "13026.fb2", "folder": "fb2-000024-030559.zip", "covered": false,
                  "compilation": [{"file": "13023.fb2", "folder": "fb2-000024-030559.zip", "part": 0},
                                  {"file": "477830.fb2", "folder": "f.fb2-476871-480576.zip", "part": 1}]}]
                """);

        assertThat(count).isEqualTo(1);
        assertThat(collected).containsOnlyKeys(
                new FlibustaCompilationParser.CompilationKey("fb2-000024-030559.zip", "13026.fb2"));
        assertThat(collected.values().iterator().next()).containsExactly(
                new CompilationPart("fb2-000024-030559.zip", "13023.fb2", 0),
                new CompilationPart("f.fb2-476871-480576.zip", "477830.fb2", 1));
    }

    @Test
    void skipsEntriesWithoutUsableKeyOrParts() {
        int count = parse("""
                [{"file": "a.fb2", "compilation": [{"file": "b.fb2", "folder": "x.zip", "part": 0}]},
                 {"file": "c.fb2", "folder": "y.zip", "compilation": []},
                 {"file": "d.fb2", "folder": "z.zip", "compilation": [{"part": 0}]}]
                """);

        assertThat(count).isZero();
        assertThat(collected).isEmpty();
    }

    /**
     * The real document is around 30 MB; the parser must keep streaming past entries it rejects
     * rather than aborting the whole file.
     */
    @Test
    void continuesAfterRejectedEntries() {
        int count = parse("""
                [{"file": "skip.fb2", "folder": "x.zip", "compilation": []},
                 {"file": "keep.fb2", "folder": "y.zip",
                  "compilation": [{"file": "p.fb2", "folder": "y.zip", "part": 0}]}]
                """);

        assertThat(count).isEqualTo(1);
        assertThat(collected).containsOnlyKeys(
                new FlibustaCompilationParser.CompilationKey("y.zip", "keep.fb2"));
    }

    @Test
    void returnsZeroWhenDocumentIsNotAnArray() {
        assertThat(parse("{\"file\": \"a.fb2\"}")).isZero();
        assertThat(collected).isEmpty();
    }

    @Test
    void stopsCleanlyOnTruncatedDocument() {
        assertThat(parse("[{\"file\": \"a.fb2\", \"folder\": \"x.zip\", \"compilation\": [")).isZero();
    }
}
