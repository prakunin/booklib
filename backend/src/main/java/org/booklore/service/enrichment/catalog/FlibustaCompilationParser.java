package org.booklore.service.enrichment.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Reads {@code compilations.json} of the Flibusta catalog:
 *
 * <pre>{@code
 * [ { "file": "13026.fb2", "folder": "fb2-000024-030559.zip", "covered": false,
 *     "compilation": [ { "file": "13023.fb2", "folder": "fb2-…zip", "part": 0 } ] } ]
 * }</pre>
 * <p>
 * The document is around 30 MB, so it is parsed as a token stream and handed to the caller one
 * compilation at a time; nothing holds the whole file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlibustaCompilationParser {

    private final ObjectMapper objectMapper;

    /**
     * @param consumer receives the compilation's own (archive, entry) key and its parts
     * @return how many compilations were read
     */
    public int parse(InputStream json, BiConsumer<CompilationKey, List<CompilationPart>> consumer) {
        int count = 0;
        // readValues over a document that starts with an array iterates its elements, so the 30 MB
        // document is walked one compilation at a time instead of being materialised as a tree.
        try (MappingIterator<JsonNode> entries = objectMapper.readerFor(JsonNode.class).readValues(json)) {
            while (entries.hasNext()) {
                if (accept(entries.next(), consumer)) {
                    count++;
                }
            }
        } catch (JacksonException e) {
            log.warn("Could not parse compilations.json after {} entries: {}", count, e.getMessage());
        } catch (Exception e) {
            log.warn("Could not read compilations.json after {} entries: {}", count, e.getMessage());
        }
        return count;
    }

    private boolean accept(JsonNode node, BiConsumer<CompilationKey, List<CompilationPart>> consumer) {
        String folder = node.path("folder").asString(null);
        String file = node.path("file").asString(null);
        if (folder == null || folder.isBlank() || file == null || file.isBlank()) {
            return false;
        }
        JsonNode parts = node.path("compilation");
        if (!parts.isArray() || parts.isEmpty()) {
            return false;
        }
        List<CompilationPart> compilationParts = new ArrayList<>();
        for (JsonNode part : parts) {
            String partFolder = part.path("folder").asString(null);
            String partFile = part.path("file").asString(null);
            if (partFolder == null || partFolder.isBlank() || partFile == null || partFile.isBlank()) {
                continue;
            }
            compilationParts.add(new CompilationPart(partFolder, partFile, part.path("part").asInt(0)));
        }
        if (compilationParts.isEmpty()) {
            return false;
        }
        consumer.accept(new CompilationKey(folder, file), compilationParts);
        return true;
    }

    public record CompilationKey(String archiveName, String entryName) {
    }
}
