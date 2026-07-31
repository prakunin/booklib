package org.booklore.service.djvu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Keeps the PDF rendition of a DjVu book: built once in the background, then served to the PDF
 * reader so the book gains searchable text, selection and annotations.
 * <p>
 * The rendition is opportunistic. It is never on the path of a user action - a DjVu book opens
 * immediately in the page reader whether or not this has run - and it is built one document at a
 * time on a single background thread, so a freshly imported shelf of scans cannot crowd out the
 * requests people are waiting on.
 * <p>
 * Staleness is structural rather than managed: the file name carries the source's modification
 * time, so a changed source can never match an existing rendition and there is no invalidation to
 * get wrong. Eviction only reclaims space.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DjvuRenditionService {

    private static final String CACHE_DIR = "djvu-renditions";
    private static final String EXTENSION = ".pdf";

    private final AppProperties appProperties;
    private final AppSettingService appSettingService;
    private final DjvuToolRunner toolRunner;
    private final DjvuPdfWriter pdfWriter;

    /** One at a time: rendering a whole document is CPU-bound and must never crowd out a page request. */
    private final ExecutorService renditionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "djvu-rendition");
        thread.setDaemon(true);
        return thread;
    });

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * The rendition of this book, if one has been built for the source file as it currently stands.
     * A source that has changed since has no rendition, by construction.
     */
    public Optional<Path> renditionPath(long bookId, Path source) {
        Path rendition = renditionFile(bookId, source);
        return rendition != null && Files.isRegularFile(rendition) ? Optional.of(rendition) : Optional.empty();
    }

    /** Whether a rendition exists and is ready to be served. */
    public boolean hasRendition(long bookId, Path source) {
        return renditionPath(bookId, source).isPresent();
    }

    /**
     * Queues the rendition unless it already exists, is already queued, or the feature is switched
     * off. Returns immediately - this is never awaited by a request.
     */
    public void requestRendition(long bookId, Path source) {
        if (!appSettingService.getAppSettings().isDjvuPdfRenditionEnabled()) {
            log.debug("PDF rendition is disabled; skipping book {}", bookId);
            return;
        }
        if (!toolRunner.isAvailable() || hasRendition(bookId, source) || !inFlight.add(bookId)) {
            return;
        }
        renditionExecutor.submit(() -> {
            try {
                build(bookId, source);
            } catch (Exception e) {
                // The book is readable without this. A failure costs the text layer, nothing else.
                log.warn("Failed to build the PDF rendition of book {}: {}", bookId, e.getMessage());
            } finally {
                inFlight.remove(bookId);
            }
        });
    }

    private void build(long bookId, Path source) {
        Path target = renditionFile(bookId, source);
        if (target == null || Files.isRegularFile(target)) {
            return;
        }

        DjvuDocumentInfo info = toolRunner.probe(source);
        log.info("Building the PDF rendition of book {} ({} pages)", bookId, info.pageCount());

        // Written beside the target and moved into place, so a process killed part way through
        // leaves no half-built PDF for the reader to open.
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try {
            pdfWriter.write(source, info, partial, null);
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(partial);
        }

        deleteSupersededRenditions(bookId, target);
        evictBeyondCacheLimit();
        log.info("PDF rendition of book {} is ready: {}", bookId, target.getFileName());
    }

    /** Older renditions of the same book, left behind when its source file changed. */
    private void deleteSupersededRenditions(long bookId, Path current) {
        listRenditions()
                .filter(path -> path.getFileName().toString().startsWith(bookId + "_"))
                .filter(path -> !path.equals(current))
                .forEach(this::deleteQuietly);
    }

    /**
     * Deletes least-recently-used renditions until the directory is back under the configured
     * ceiling. A library of hundreds of scans would otherwise turn a nice-to-have into a full disk.
     */
    private void evictBeyondCacheLimit() {
        Integer limitMb = appSettingService.getAppSettings().getDjvuRenditionCacheSizeInMb();
        if (limitMb == null || limitMb <= 0) {
            return;
        }
        long limitBytes = limitMb * 1024L * 1024L;

        List<Path> renditions = listRenditions()
                .sorted(Comparator.comparingLong(this::lastAccess))
                .toList();
        long total = renditions.stream().mapToLong(this::size).sum();

        for (Path rendition : renditions) {
            if (total <= limitBytes) {
                return;
            }
            long size = size(rendition);
            if (deleteQuietly(rendition)) {
                total -= size;
                log.info("Evicted the PDF rendition {} to stay under the {} MB cache limit",
                        rendition.getFileName(), limitMb);
            }
        }
    }

    private Stream<Path> listRenditions() {
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .toList()
                    .stream();
        } catch (IOException e) {
            log.debug("Could not list the rendition cache: {}", e.getMessage());
            return Stream.empty();
        }
    }

    private long lastAccess(Path path) {
        try {
            return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class)
                    .lastAccessTime().toMillis();
        } catch (IOException _) {
            return 0L;
        }
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException _) {
            return 0L;
        }
    }

    private boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not delete {}: {}", path, e.getMessage());
            return false;
        }
    }

    /** @return the path this book's rendition would have, or null if the source cannot be read. */
    private Path renditionFile(long bookId, Path source) {
        try {
            long lastModified = Files.getLastModifiedTime(source).toMillis();
            return cacheDir().resolve(bookId + "_" + lastModified + EXTENSION);
        } catch (IOException e) {
            log.debug("Could not stat {}: {}", source, e.getMessage());
            return null;
        }
    }

    private Path cacheDir() {
        return Path.of(appProperties.getPathConfig(), "cache", CACHE_DIR);
    }
}
