package org.booklore.service.inpx;

import org.booklore.config.AppProperties;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extraction cache has no ceiling of its own: every archived book ever opened stays on disk
 * forever. These cover the two halves of bounding it - reads that decline to cache, and the
 * least-recently-used sweep that the nightly cleanup task drives.
 */
class ArchivedBookContentCacheLimitTest {

    @TempDir
    Path tempDir;

    private Path archiveRoot;

    private ArchivedBookContentService service() {
        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        return new ArchivedBookContentService(properties, new ArchiveService());
    }

    private Path cacheRoot() {
        return tempDir.resolve("data/cache/inpx");
    }

    private BookFileEntity archivedBook(long fileId, String entryName, String content) throws IOException {
        if (archiveRoot == null) {
            archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        }
        Path archive = archiveRoot.resolve("fb2-1-100.zip");
        boolean exists = Files.exists(archive);
        // Rebuilt from scratch each time: a zip is not appendable, and the fixtures are tiny.
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        if (exists) {
            try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
                var names = zip.entries();
                while (names.hasMoreElements()) {
                    ZipEntry entry = names.nextElement();
                    entries.put(entry.getName(),
                            new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        entries.put(entryName, content);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }

        LibraryEntity library = LibraryEntity.builder()
                .id(7L)
                .inpxArchivePath(archiveRoot.toString())
                .build();
        BookEntity book = BookEntity.builder().library(library).build();
        return BookFileEntity.builder()
                .id(fileId)
                .book(book)
                .fileName(entryName)
                .fileSubPath("")
                .sourceArchive("fb2-1-100.zip")
                .sourceArchiveEntry(entryName)
                .build();
    }

    /**
     * The data volume is mounted noatime, so lastAccessTime never moves after creation and cannot
     * order the cache. Serving a hit therefore has to stamp mtime itself, or eviction would drop
     * exactly the books that are read most.
     */
    @Test
    void servingFromTheCacheStampsModificationTimeSoEvictionCanSeeTheRead() throws IOException {
        ArchivedBookContentService service = service();
        BookFileEntity file = archivedBook(9L, "42.fb2", "<FictionBook>hot</FictionBook>");

        Path cached = service.resolve(file);
        FileTime backdated = FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS));
        Files.setLastModifiedTime(cached, backdated);

        Path secondRead = service.resolve(file);

        assertThat(secondRead).isEqualTo(cached);
        assertThat(Files.getLastModifiedTime(cached)).isGreaterThan(backdated);
    }

    @Test
    void temporaryCopyReadsTheBookWithoutLeavingItInTheCache() throws IOException {
        ArchivedBookContentService service = service();
        BookFileEntity file = archivedBook(9L, "42.fb2", "<FictionBook>scanned</FictionBook>");

        String content = service.withPublicationCopy(file, path -> {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        assertThat(content).isEqualTo("<FictionBook>scanned</FictionBook>");
        assertThat(cacheRoot().resolve("7/9/42.fb2")).doesNotExist();
    }

    @Test
    void temporaryCopyDeletesTheScratchFileAfterTheReaderReturns() throws IOException {
        ArchivedBookContentService service = service();
        BookFileEntity file = archivedBook(9L, "42.fb2", "<FictionBook>scanned</FictionBook>");

        Path seen = service.withPublicationCopy(file, path -> path);

        assertThat(seen).doesNotExist();
    }

    @Test
    void temporaryCopyReusesAnAlreadyCachedExtractionAndKeepsIt() throws IOException {
        ArchivedBookContentService service = service();
        BookFileEntity file = archivedBook(9L, "42.fb2", "<FictionBook>already here</FictionBook>");
        Path cached = service.resolve(file);

        Path seen = service.withPublicationCopy(file, path -> path);

        assertThat(seen).isEqualTo(cached);
        assertThat(cached).exists().hasContent("<FictionBook>already here</FictionBook>");
    }

    /** A plain book on disk has no archive to extract from, and must not be deleted as scratch. */
    @Test
    void temporaryCopyPassesThroughPlainBooksWithoutDeletingThem() throws IOException {
        ArchivedBookContentService service = service();
        Path onDisk = Files.writeString(tempDir.resolve("plain.fb2"), "<FictionBook>plain</FictionBook>");

        LibraryEntity library = LibraryEntity.builder().id(7L).build();
        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(LibraryPathEntity.builder().path(tempDir.toString()).build())
                .build();
        BookFileEntity file = BookFileEntity.builder()
                .id(11L)
                .book(book)
                .fileName("plain.fb2")
                .fileSubPath("")
                .build();

        Path seen = service.withPublicationCopy(file, path -> path);

        assertThat(seen).isEqualTo(onDisk);
        assertThat(onDisk).exists();
    }

    @Test
    void evictionDropsLeastRecentlyUsedEntriesUntilTheCacheIsUnderTheLimit() throws IOException {
        ArchivedBookContentService service = service();
        Path cold = cachedEntry("7/1/cold.fb2", 4096, Instant.now().minus(10, ChronoUnit.DAYS));
        Path warm = cachedEntry("7/2/warm.fb2", 4096, Instant.now().minus(2, ChronoUnit.DAYS));
        Path hot = cachedEntry("7/3/hot.fb2", 4096, Instant.now());

        var result = service.evictBeyondCacheLimit(9 * 1024L);

        assertThat(cold).doesNotExist();
        assertThat(warm).exists();
        assertThat(hot).exists();
        assertThat(result.deletedFiles()).isEqualTo(1);
        assertThat(result.freedBytes()).isEqualTo(4096);
    }

    @Test
    void evictionRemovesTheBookDirectoryItJustEmptied() throws IOException {
        ArchivedBookContentService service = service();
        cachedEntry("7/1/cold.fb2", 4096, Instant.now().minus(10, ChronoUnit.DAYS));
        cachedEntry("7/2/hot.fb2", 4096, Instant.now());

        service.evictBeyondCacheLimit(4096L);

        assertThat(cacheRoot().resolve("7/1")).doesNotExist();
        assertThat(cacheRoot().resolve("7/2")).exists();
    }

    @Test
    void evictionIsDisabledByANonPositiveLimit() throws IOException {
        ArchivedBookContentService service = service();
        Path entry = cachedEntry("7/1/cold.fb2", 4096, Instant.now().minus(10, ChronoUnit.DAYS));

        var result = service.evictBeyondCacheLimit(0L);

        assertThat(entry).exists();
        assertThat(result.deletedFiles()).isZero();
    }

    @Test
    void evictionToleratesACacheThatWasNeverCreated() {
        ArchivedBookContentService service = service();

        var result = service.evictBeyondCacheLimit(1024L);

        assertThat(result.deletedFiles()).isZero();
        assertThat(result.freedBytes()).isZero();
    }

    private Path cachedEntry(String relativePath, int size, Instant modifiedAt) throws IOException {
        Path entry = cacheRoot().resolve(relativePath);
        Files.createDirectories(entry.getParent());
        Files.write(entry, new byte[size]);
        Files.setLastModifiedTime(entry, FileTime.from(modifiedAt));
        return entry;
    }
}
