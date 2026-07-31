package org.booklore.service.metadata.extractor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.dto.BookMetadata;
import org.booklore.service.djvu.DjvuDocumentInfo;
import org.booklore.service.djvu.DjvuToolException;
import org.booklore.service.djvu.DjvuToolRunner;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads what a DjVu file can say about itself.
 * <p>
 * In practice that is very little: DjVu carries an optional annotation chunk, and the scanned
 * documents this format is used for almost never have one. That is by design here, not a gap -
 * whatever stays blank falls back to the filename baseline and is then refined by Smart Enrichment
 * from the metadata providers, which knows far more about a book than a scanner ever wrote into it.
 * Guessing here would only outrank a better answer later.
 * <p>
 * The cover is the first page, rendered. Unlike the container formats, a DjVu file always has one,
 * so this extractor never reports a clean miss - see {@link #extractCover}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DjvuMetadataExtractor implements FileMetadataExtractor {

    /**
     * Covers are downscaled to thumbnails downstream, so rendering a scan at its full size would
     * only cost memory. Large enough that the stored cover is still sharp.
     */
    private static final int COVER_MAX_EDGE_PIXELS = 1200;

    private static final Pattern MULTI_AUTHOR_SEPARATOR = Pattern.compile("\\s*;\\s*");
    private static final Pattern YEAR_ONLY = Pattern.compile("\\d{4}");

    private final DjvuToolRunner toolRunner;

    @Override
    public BookMetadata extractMetadata(File file) {
        try {
            DjvuDocumentInfo info = toolRunner.probe(file.toPath());
            return build(info.metadata());
        } catch (DjvuToolException e) {
            log.warn("Failed to read DjVu metadata from {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Never returns {@code null}: page 1 exists in every DjVu document, so "the file has no cover"
     * is not a state this format can be in. Anything that stops the render from happening is a
     * failed read and is reported as one.
     */
    @Override
    public byte[] extractCover(File file) {
        ByteArrayOutputStream cover = new ByteArrayOutputStream();
        try {
            toolRunner.renderPageAsJpeg(file.toPath(), 1, COVER_MAX_EDGE_PIXELS, cover);
        } catch (DjvuToolException e) {
            throw new CoverExtractionException("Failed to render the first page of " + file.getName(), e);
        }
        return cover.toByteArray();
    }

    private BookMetadata build(Map<String, String> raw) {
        Map<String, String> metadata = lowerCaseKeys(raw);
        return BookMetadata.builder()
                .title(value(metadata, "title"))
                .authors(authors(metadata))
                .publisher(value(metadata, "publisher"))
                .language(value(metadata, "language"))
                .publishedDate(publishedDate(metadata))
                .build();
    }

    private Map<String, String> lowerCaseKeys(Map<String, String> raw) {
        return raw.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(Locale.ROOT),
                        Map.Entry::getValue,
                        (first, _) -> first));
    }

    private String value(Map<String, String> metadata, String key) {
        return StringUtils.trimToNull(metadata.get(key));
    }

    /**
     * Only a semicolon splits authors. A comma is how a single name is written the other way round
     * ("Popov, A."), so splitting on it would turn one author into two.
     */
    private List<String> authors(Map<String, String> metadata) {
        String author = value(metadata, "author");
        if (author == null) {
            return List.of();
        }
        return Arrays.stream(MULTI_AUTHOR_SEPARATOR.split(author))
                .map(String::strip)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    /**
     * Accepts both an ISO date and the bare year that scanning tools actually write, mapping the
     * latter to the first of January - the same convention the rest of the catalog uses for an
     * edition known only by its year.
     */
    private LocalDate publishedDate(Map<String, String> metadata) {
        String raw = Optional.ofNullable(value(metadata, "year")).orElseGet(() -> value(metadata, "date"));
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException _) {
            var year = YEAR_ONLY.matcher(raw);
            return year.find() ? LocalDate.of(Integer.parseInt(year.group()), 1, 1) : null;
        }
    }
}
