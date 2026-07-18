package org.booklore.service.reader;

import org.booklore.config.AppProperties;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.booklore.exception.APIException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChapterCacheServiceTest {

    @Mock
    private AppProperties appProperties;

    @Mock
    private ArchiveService archiveService;

    @TempDir
    private Path tempDir;

    private ChapterCacheService chapterCacheService;

    @BeforeEach
    void setUp() {
        chapterCacheService = new ChapterCacheService(appProperties, archiveService);
    }

    @Test
    void getCachedPage_withTraversal_throwsException() {
        assertThrows(APIException.class, () ->
            chapterCacheService.getCachedPage("../outside", 1)
        );
    }

    @Test
    void getCachedPage_withPathSeparator_throwsException() {
        assertThrows(APIException.class, () ->
            chapterCacheService.getCachedPage("sub/folder", 1)
        );
    }

    @Test
    void hasPage_withTraversal_throwsException() {
        assertThrows(APIException.class, () ->
            chapterCacheService.hasPage("../outside", 1)
        );
    }

    @Test
    void cacheLocksAreBoundedAndExpireAfterAccess() {
        var eviction = chapterCacheService.cacheLocks().policy().eviction();
        var expiration = chapterCacheService.cacheLocks().policy().expireAfterAccess();

        assertThat(eviction).isPresent();
        assertThat(eviction.get().getMaximum()).isEqualTo(ChapterCacheService.CACHE_LOCK_MAX_ENTRIES);
        assertThat(expiration).isPresent();
        assertThat(expiration.get().getExpiresAfter()).isEqualTo(ChapterCacheService.CACHE_LOCK_TTL);
    }

    @Test
    void cleanupStaleCacheDirsDeletesOnlySameCacheFamily() throws Exception {
        when(appProperties.getPathConfig()).thenReturn(tempDir.toString());
        Path cacheRoot = tempDir.resolve("cache").resolve("chapters");
        Path stale = cacheRoot.resolve("10_CBX_1000");
        Path current = cacheRoot.resolve("10_CBX_2000");
        Path otherType = cacheRoot.resolve("10_PDF_1000");
        Path otherBook = cacheRoot.resolve("11_CBX_1000");

        Files.createDirectories(stale);
        Files.createDirectories(current);
        Files.createDirectories(otherType);
        Files.createDirectories(otherBook);
        Files.writeString(stale.resolve("page_1.jpg"), "old");

        chapterCacheService.cleanupStaleCacheDirs("10_CBX_2000");

        assertThat(stale).doesNotExist();
        assertThat(current).exists();
        assertThat(otherType).exists();
        assertThat(otherBook).exists();
    }
}
