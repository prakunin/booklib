package org.booklore.service.inpx;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.dto.inpx.InpxBookReference;
import org.booklore.model.dto.inpx.InpxSearchResult;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Component
public class InpxParser {

    private static final char FIELD_SEPARATOR = 0x04;
    private static final String DEFAULT_STRUCTURE =
            "AUTHOR;GENRE;TITLE;SERIES;SERNO;FILE;SIZE;LIBID;DEL;EXT;DATE;LANG;LIBRATE;KEYWORDS";

    public InpxSearchResult search(String inpxPath, String query, int requestedLimit) {
        int limit = Math.clamp(requestedLimit, 1, 500);
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<InpxBookDto> matches = new ArrayList<>();
        long[] scanned = {0};
        boolean[] truncated = {false};

        parse(inpxPath, book -> {
            scanned[0]++;
            if (!matchesQuery(book, normalizedQuery)) {
                return;
            }
            if (matches.size() < limit) {
                matches.add(book);
            } else {
                truncated[0] = true;
            }
        });

        return InpxSearchResult.builder()
                .books(matches)
                .scannedCount(scanned[0])
                .truncated(truncated[0])
                .build();
    }

    public Map<String, InpxBookDto> resolve(String inpxPath, List<InpxBookReference> references) {
        Set<String> requestedIds = new HashSet<>();
        for (InpxBookReference reference : references) {
            requestedIds.add(id(reference.getArchiveName(), reference.getFileName(), reference.getExtension()));
        }

        Map<String, InpxBookDto> resolved = new LinkedHashMap<>();
        parse(inpxPath, book -> {
            if (requestedIds.contains(book.getId())) {
                resolved.putIfAbsent(book.getId(), book);
            }
        });
        return resolved;
    }

    public void forEach(String inpxPath, Consumer<InpxBookDto> consumer) {
        parse(inpxPath, consumer);
    }

    public long count(String inpxPath) {
        long[] total = {0};
        parse(inpxPath, book -> total[0]++);
        return total[0];
    }

    private void parse(String inpxPath, Consumer<InpxBookDto> consumer) {
        Path indexPath = validateIndexPath(inpxPath);
        try (ZipFile inpx = new ZipFile(indexPath.toFile(), StandardCharsets.UTF_8)) {
            Map<String, Integer> structure = readStructure(inpx);
            for (ZipEntry entry : Collections.list(inpx.entries())) {
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".inp")) {
                    continue;
                }
                String archiveName = Path.of(entry.getName()).getFileName().toString()
                        .replaceFirst("(?i)\\.inp$", ".zip");
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inpx.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        InpxBookDto book = parseLine(line, archiveName, structure);
                        if (book != null) {
                            consumer.accept(book);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read INPX index {}: {}", indexPath, e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException("Unable to read INPX index: " + e.getMessage());
        }
    }

    private Path validateIndexPath(String inpxPath) {
        if (inpxPath == null || inpxPath.isBlank() || inpxPath.indexOf('\0') >= 0) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("INPX path is required");
        }
        try {
            Path path = Path.of(inpxPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !Files.isReadable(path)
                    || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".inpx")) {
                throw ApiError.GENERIC_BAD_REQUEST.createException("INPX path must reference a readable .inpx file");
            }
            return path;
        } catch (InvalidPathException _) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid INPX path");
        }
    }

    private Map<String, Integer> readStructure(ZipFile inpx) throws IOException {
        ZipEntry structureEntry = inpx.getEntry("structure.info");
        String value = DEFAULT_STRUCTURE;
        if (structureEntry != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inpx.getInputStream(structureEntry), StandardCharsets.UTF_8))) {
                String configured = reader.readLine();
                if (configured != null && !configured.isBlank()) {
                    value = configured.replace("\uFEFF", "").strip();
                }
            }
        }

        Map<String, Integer> fields = new HashMap<>();
        String[] names = value.split(";");
        for (int i = 0; i < names.length; i++) {
            fields.put(names[i].strip().toUpperCase(Locale.ROOT), i);
        }
        return fields;
    }

    private InpxBookDto parseLine(String line, String archiveName, Map<String, Integer> structure) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] values = line.split(String.valueOf(FIELD_SEPARATOR), -1);
        String title = field(values, structure, "TITLE");
        String fileName = field(values, structure, "FILE");
        String extension = field(values, structure, "EXT").toLowerCase(Locale.ROOT);
        if (title.isBlank() || fileName.isBlank() || !"fb2".equals(extension)) {
            return null;
        }
        String deleted = field(values, structure, "DEL");
        if (!deleted.isBlank() && !"0".equals(deleted)) {
            return null;
        }

        return InpxBookDto.builder()
                .id(id(archiveName, fileName, extension))
                .authors(splitList(field(values, structure, "AUTHOR"), true))
                .genres(splitList(field(values, structure, "GENRE"), false))
                .title(title)
                .series(field(values, structure, "SERIES"))
                .seriesNumber(field(values, structure, "SERNO"))
                .fileName(fileName)
                .extension(extension)
                .libraryId(field(values, structure, "LIBID"))
                .date(field(values, structure, "DATE"))
                .language(field(values, structure, "LANG"))
                .rating(parseRating(field(values, structure, "LIBRATE")))
                .archiveName(archiveName)
                .build();
    }

    private String field(String[] values, Map<String, Integer> structure, String name) {
        Integer index = structure.get(name);
        return index != null && index < values.length ? values[index].strip() : "";
    }

    private List<String> splitList(String value, boolean authors) {
        if (value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(":"))
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .map(item -> authors ? item.replace(',', ' ').replaceAll("\\s+", " ") : item)
                .toList();
    }

    private boolean matchesQuery(InpxBookDto book, String query) {
        if (query.isBlank()) {
            return true;
        }
        return book.getTitle().toLowerCase(Locale.ROOT).contains(query)
                || book.getSeries().toLowerCase(Locale.ROOT).contains(query)
                || book.getLibraryId().toLowerCase(Locale.ROOT).contains(query)
                || book.getAuthors().stream().anyMatch(author -> author.toLowerCase(Locale.ROOT).contains(query));
    }

    private Double parseRating(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.strip().replace(',', '.'));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public static String id(String archiveName, String fileName, String extension) {
        return archiveName + '|' + fileName + '.' + extension;
    }
}
