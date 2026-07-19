package org.booklore.service.reader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.service.ArchiveService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service for managing the on-disk extraction cache for reader chapters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterCacheService {

    private static final long MTIME_TOLERANCE_MS = 2000;
    static final int CACHE_LOCK_MAX_ENTRIES = 1024;
    static final Duration CACHE_LOCK_TTL = Duration.ofMinutes(30);
    private static final String PAGE_FILE_PREFIX = "page_";

    private final AppProperties appProperties;
    private final ArchiveService archiveService;
    private final Cache<String, ReentrantLock> cacheLocks = Caffeine.newBuilder()
            .maximumSize(CACHE_LOCK_MAX_ENTRIES)
            .expireAfterAccess(CACHE_LOCK_TTL)
            .build();

    /**
     * Ensures all pages of a CBX archive are extracted to the disk cache.
     * Extracts pages sequentially to avoid concurrent native libarchive access
     * which can cause SIGSEGV / out-of-memory crashes in the native heap.
     */
    public void prepareCbxCache(String cacheKey, Path cbxPath, List<String> entries) throws IOException {
        ReentrantLock lock = cacheLocks.get(cacheKey, _ -> new ReentrantLock());
        lock.lock();
        try {
            cleanupStaleCacheDirs(cacheKey);
            Path cacheDir = getCacheDir(cacheKey);
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }

            // Only extract if the cache is empty or stale
            if (isCacheStale(cacheDir, cbxPath, entries.size())) {
                log.info("Populating disk cache for {}: {} pages", cacheKey, entries.size());

                for (int i = 0; i < entries.size(); i++) {
                    Path target = cacheDir.resolve(PAGE_FILE_PREFIX + (i + 1) + ".jpg");
                    if (!Files.exists(target) || Files.size(target) == 0) {
                        String entryName = entries.get(i);
                        writeAtomically(target, out ->
                                archiveService.transferEntryTo(cbxPath, entryName, out));
                    }
                }

                // Mark cache as fresh by setting its mtime to match the archive
                Files.setLastModifiedTime(cacheDir, Files.getLastModifiedTime(cbxPath));
            }
        } finally {
            lock.unlock();
        }
    }

    Cache<String, ReentrantLock> cacheLocks() {
        return cacheLocks;
    }

    void cleanupStaleCacheDirs(String currentCacheKey) {
        Path currentCacheDir = getCacheDir(currentCacheKey);
        Path cacheRoot = currentCacheDir.getParent();
        if (cacheRoot == null || !Files.isDirectory(cacheRoot)) {
            return;
        }

        String cacheKeyPrefix = cacheKeyPrefix(currentCacheKey);
        try (Stream<Path> dirs = Files.list(cacheRoot)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(currentCacheDir))
                    .filter(dir -> dir.getFileName().toString().startsWith(cacheKeyPrefix))
                    .forEach(this::deleteRecursively);
        } catch (IOException e) {
            log.debug("Failed to clean stale chapter cache dirs for key {}: {}", currentCacheKey, e.getMessage());
        }
    }

    public Path getCachedPage(String cacheKey, int pageNumber) {
        return getCacheDir(cacheKey).resolve(PAGE_FILE_PREFIX + pageNumber + ".jpg");
    }

    public boolean hasPage(String cacheKey, int pageNumber) {
        Path pagePath = getCachedPage(cacheKey, pageNumber);
        try {
            return Files.exists(pagePath) && Files.size(pagePath) > 0;
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * Writes data to a temp file in the same directory, then atomically moves
     * it to the target path. If the write fails, the partial temp file is
     * cleaned up and the target is never touched.
     */
    void writeAtomically(Path target, IOConsumer<OutputStream> writer) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                writer.accept(out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T t) throws IOException;
    }

    private Path getCacheDir(String cacheKey) {
        if (cacheKey == null || cacheKey.contains("..") || cacheKey.contains("/") || cacheKey.contains("\\")) {
            throw ApiError.INVALID_INPUT.createException("Invalid cache key: " + cacheKey);
        }
        return Paths.get(appProperties.getPathConfig(), "cache", "chapters", cacheKey);
    }

    private String cacheKeyPrefix(String cacheKey) {
        int lastSeparator = cacheKey.lastIndexOf('_');
        return lastSeparator >= 0 ? cacheKey.substring(0, lastSeparator + 1) : cacheKey + "_";
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Failed to delete stale chapter cache path {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Failed to walk stale chapter cache dir {}: {}", dir, e.getMessage());
        }
    }

    private boolean isCacheStale(Path cacheDir, Path sourcePath, int expectedPages) throws IOException {
        if (!Files.exists(cacheDir)) return true;

        for (int i = 1; i <= expectedPages; i++) {
            Path page = cacheDir.resolve(PAGE_FILE_PREFIX + i + ".jpg");
            if (!Files.exists(page) || Files.size(page) == 0) return true;
        }

        long cacheMtime = Files.getLastModifiedTime(cacheDir).toMillis();
        long sourceMtime = Files.getLastModifiedTime(sourcePath).toMillis();
        return Math.abs(cacheMtime - sourceMtime) > MTIME_TOLERANCE_MS;
    }
}
