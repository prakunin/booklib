package org.booklore.service.enrichment.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds the local catalog that ships next to a library's archives.
 * <p>
 * The convention these libraries follow is a sibling directory named after the archive folder:
 * {@code …/fb2.Flibusta.Net/} is accompanied by {@code …/fb2.Flibusta.Net.FLibrary.etc/}. The exact
 * sibling is preferred; any other matching sibling is a fallback, because a catalog that does not
 * belong to these archives would join on keys that do not exist and simply find nothing.
 * <p>
 * Detection only ever produces a suggestion. Nothing here writes the path onto a library — pointing
 * a library at a catalog is the user's decision, and a wrong guess applied silently would be
 * invisible until descriptions started appearing from the wrong source.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalCatalogDetector {

    private static final String CATALOG_SUFFIX = ".FLibrary.etc";

    private final FlibustaCatalogLayout layout;

    public Optional<Path> detect(String archiveDirectory) {
        if (archiveDirectory == null || archiveDirectory.isBlank()) {
            return Optional.empty();
        }
        Path archives = Path.of(archiveDirectory.strip());
        Path parent = archives.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return Optional.empty();
        }
        Path exact = parent.resolve(archives.getFileName() + CATALOG_SUFFIX);
        if (layout.matches(exact)) {
            return Optional.of(exact);
        }
        return anyCatalogIn(parent);
    }

    private Optional<Path> anyCatalogIn(Path parent) {
        try (Stream<Path> siblings = Files.list(parent)) {
            return siblings
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith(CATALOG_SUFFIX))
                    .filter(layout::matches)
                    .min(Comparator.comparing(path -> path.getFileName().toString()));
        } catch (IOException e) {
            log.debug("Could not scan {} for a local catalog: {}", parent, e.getMessage());
            return Optional.empty();
        }
    }
}
