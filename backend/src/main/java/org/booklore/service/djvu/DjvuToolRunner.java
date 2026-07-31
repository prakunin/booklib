package org.booklore.service.djvu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that knows how to talk to djvulibre.
 * <p>
 * Everything above this class deals in page counts, sizes and JPEG bytes; the command lines, the
 * timeouts and the PPM decoding stop here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DjvuToolRunner {

    static final String DDJVU = "ddjvu";
    static final String DJVUSED = "djvused";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Sizes are probed with one generated script rather than one process per page. The cap keeps a
     * pathological document from building a megabyte-long argument; past it, sizes are simply not
     * reported and the reader falls back to laying pages out as it receives them.
     */
    private static final int MAX_PAGES_TO_SIZE = 5000;

    private static final Pattern SIZE_LINE = Pattern.compile("width=(\\d+)\\s+height=(\\d+)");
    private static final Pattern META_LINE = Pattern.compile("^(\\S+)\\s+\"(.*)\"\\s*$");
    /** {@code (word 10 100 60 140 "Hello")} - a leaf zone of the hidden text tree. */
    private static final Pattern WORD_ZONE = Pattern.compile(
            "\\(word\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");
    /** "DJVUSED --- DjVuLibre-3.5.28" is the whole banner these tools print. */
    private static final Pattern VERSION_BANNER = Pattern.compile("(DjVuLibre-[\\d.]+)");

    private final FileService fileService;
    private final DjvuCommandRunner commandRunner;

    private final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.empty());

    /** Whether the decoder is present at all. False in an image built without djvulibre. */
    public boolean isAvailable() {
        return fileService.findSystemFile(DDJVU) != null;
    }

    /**
     * The installed djvulibre version, for the diagnostics page.
     * <p>
     * Memoised on success: the binary is baked into the image and cannot change without a restart,
     * so probing it per page view would fork a process for an answer that never changes. A failure
     * is not cached - a loaded host must not make the tool look permanently absent.
     */
    public Optional<String> version() {
        Optional<String> memoised = version.get();
        if (memoised.isPresent()) {
            return memoised;
        }
        Path djvused = fileService.findSystemFile(DJVUSED);
        if (djvused == null) {
            return Optional.empty();
        }
        Optional<String> probed = commandRunner
                .firstStderrLine(djvused, List.of("--help"), PROBE_TIMEOUT)
                .map(VERSION_BANNER::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1));
        probed.ifPresent(found -> version.set(Optional.of(found)));
        return probed;
    }

    /**
     * Reads structure and embedded metadata without rendering anything.
     *
     * @throws DjvuToolException if djvused is missing or the document cannot be read
     */
    public DjvuDocumentInfo probe(Path file) {
        Path djvused = binary(DJVUSED);
        String path = file.toAbsolutePath().toString();

        int pageCount = parsePageCount(run(djvused, List.of("-e", "n", path), PROBE_TIMEOUT));
        List<DjvuDocumentInfo.PageSize> sizes = probeSizes(djvused, path, pageCount);
        Map<String, String> metadata = parseMetadata(
                run(djvused, List.of("-e", "print-meta; select 1; print-meta", path), PROBE_TIMEOUT));

        return new DjvuDocumentInfo(pageCount, sizes, metadata);
    }

    /**
     * Renders one page and writes it as JPEG.
     *
     * @param maxEdgePixels longest edge the rendered page may have, or a non-positive value to
     *                      render at the document's natural size. A cap is a guard against a scan
     *                      whose natural size would be tens of megapixels, not a quality setting:
     *                      ddjvu preserves the aspect ratio and only ever fits the page inside the
     *                      box.
     * @throws DjvuToolException if ddjvu is missing, the page cannot be decoded, or the output is
     *                           not the PPM we asked for
     */
    public void renderPageAsJpeg(Path file, int pageNumber, int maxEdgePixels, OutputStream jpegOut) {
        Path ddjvu = binary(DDJVU);

        List<String> args = new ArrayList<>(List.of("-format=ppm", "-page=" + pageNumber));
        if (maxEdgePixels > 0) {
            args.add("-size=" + maxEdgePixels + "x" + maxEdgePixels);
        }
        args.add(file.toAbsolutePath().toString());
        args.add("-");

        ByteArrayOutputStream ppm = new ByteArrayOutputStream();
        commandRunner.binary(ddjvu, args, ppm, RENDER_TIMEOUT);

        BufferedImage image = PpmImage.decode(ppm.toByteArray());
        try {
            if (!ImageIO.write(image, "jpeg", jpegOut)) {
                throw new DjvuToolException("No JPEG writer available for a rendered DjVu page");
            }
        } catch (IOException e) {
            throw new DjvuToolException("Failed to encode page " + pageNumber + " of " + file.getFileName(), e);
        }
    }

    /**
     * The hidden text of one page, word by word, or an empty list when the page carries none.
     * <p>
     * Most scans have no text layer at all - it is there only when the file was OCR'd - so an empty
     * result is the normal case and never an error.
     */
    public List<DjvuTextWord> pageText(Path file, int pageNumber) {
        Path djvused = binary(DJVUSED);
        String output = run(djvused,
                List.of("-e", "select " + pageNumber + "; print-txt", file.toAbsolutePath().toString()),
                PROBE_TIMEOUT);
        return parseWords(output);
    }

    private List<DjvuTextWord> parseWords(String output) {
        List<DjvuTextWord> words = new ArrayList<>();
        Matcher matcher = WORD_ZONE.matcher(output);
        while (matcher.find()) {
            String text = matcher.group(5).replace("\\\"", "\"").replace("\\\\", "\\");
            if (text.isBlank()) {
                continue;
            }
            words.add(new DjvuTextWord(text,
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4))));
        }
        return words;
    }

    private List<DjvuDocumentInfo.PageSize> probeSizes(Path djvused, String path, int pageCount) {
        if (pageCount <= 0) {
            return List.of();
        }
        if (pageCount > MAX_PAGES_TO_SIZE) {
            log.info("Skipping page-size probe for {}: {} pages exceeds the {} page cap",
                    path, pageCount, MAX_PAGES_TO_SIZE);
            return List.of();
        }
        StringBuilder script = new StringBuilder();
        for (int page = 1; page <= pageCount; page++) {
            script.append("select ").append(page).append("; size; ");
        }
        return parseSizes(run(djvused, List.of("-e", script.toString(), path), PROBE_TIMEOUT));
    }

    private String run(Path binary, List<String> args, Duration timeout) {
        return commandRunner.text(binary, args, timeout);
    }

    private Path binary(String name) {
        Path binary = fileService.findSystemFile(name);
        if (binary == null) {
            throw new DjvuToolException("Could not find the " + name + " binary; DjVu support needs djvulibre");
        }
        return binary;
    }

    private int parsePageCount(String output) {
        String first = output.strip().lines().findFirst().orElse("").strip();
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException e) {
            throw new DjvuToolException("djvused did not report a page count: " + first, e);
        }
    }

    private List<DjvuDocumentInfo.PageSize> parseSizes(String output) {
        List<DjvuDocumentInfo.PageSize> sizes = new ArrayList<>();
        Matcher matcher = SIZE_LINE.matcher(output);
        while (matcher.find()) {
            sizes.add(new DjvuDocumentInfo.PageSize(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))));
        }
        return sizes;
    }

    /**
     * Parses {@code print-meta} output. Unparseable lines are dropped rather than failing the
     * probe: metadata is a bonus on a format that usually carries none, and a malformed record
     * must not cost the caller its page count.
     */
    private Map<String, String> parseMetadata(String output) {
        Map<String, String> metadata = new LinkedHashMap<>();
        output.lines().forEach(line -> {
            Matcher matcher = META_LINE.matcher(line.strip());
            if (matcher.matches()) {
                metadata.put(matcher.group(1), matcher.group(2).replace("\\\"", "\""));
            }
        });
        return metadata;
    }
}
