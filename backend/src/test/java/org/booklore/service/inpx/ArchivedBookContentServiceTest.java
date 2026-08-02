package org.booklore.service.inpx;

import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream.UnicodeExtraFieldPolicy;
import org.booklore.config.AppProperties;
import org.booklore.exception.ArchiveEntryMissingException;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.service.ArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchivedBookContentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsArchivedBookIntoApplicationCacheAndReusesIt() throws IOException {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        Path archive = archiveRoot.resolve("fb2-1-100.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("42.fb2"));
            output.write("<FictionBook>cached</FictionBook>".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        ArchivedBookContentService service = new ArchivedBookContentService(properties, new ArchiveService());

        LibraryEntity library = LibraryEntity.builder()
                .id(7L)
                .inpxArchivePath(archiveRoot.toString())
                .build();
        BookEntity book = BookEntity.builder().library(library).build();
        BookFileEntity file = BookFileEntity.builder()
                .id(9L)
                .book(book)
                .fileName("42.fb2")
                .fileSubPath("")
                .sourceArchive("fb2-1-100.zip")
                .sourceArchiveEntry("42.fb2")
                .build();

        Path first = service.resolve(file);
        Path second = service.resolve(file);

        assertThat(first)
                .isEqualTo(second)
                .hasContent("<FictionBook>cached</FictionBook>")
                .startsWith(tempDir.resolve("data/cache/inpx/7/9"));
        assertThat(archive).exists();
    }

    @Test
    void extractsANonFb2ArchiveEntry() throws IOException {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        Path archive = archiveRoot.resolve("usr-1.zip");
        writeArchive(archive, "paper.doc", "doc-bytes");
        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        ArchivedBookContentService service = new ArchivedBookContentService(properties, new ArchiveService());
        LibraryEntity library = LibraryEntity.builder().id(7L).inpxArchivePath(archiveRoot.toString()).build();
        BookEntity book = BookEntity.builder().library(library).build();
        BookFileEntity file = BookFileEntity.builder()
                .id(11L)
                .book(book)
                .fileName("paper.doc")
                .fileSubPath("")
                .sourceArchive("usr-1.zip")
                .sourceArchiveEntry("paper.doc")
                .build();

        Path resolved = service.resolve(file);

        assertThat(resolved).hasContent("doc-bytes");
        assertThat(resolved.getFileName()).hasToString("paper.doc");
    }

    @Test
    void extractsADirectLegacyCyrillicEntryByItsResolvedName() throws IOException {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        ZipCharsetTestFixtures.write(archiveRoot.resolve("usr-1.zip"), "IBM866", false,
                UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Пушкин.fb2", "legacy-content"));
        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        ArchivedBookContentService service = new ArchivedBookContentService(properties, new ArchiveService());
        BookFileEntity file = archivedFile(archiveRoot, "usr-1.zip", "Пушкин.fb2");

        Path resolved = service.resolve(file);

        assertThat(resolved).hasContent("legacy-content");
        assertThat(resolved.getFileName()).hasToString("Пушкин.fb2");
    }

    @Test
    void extractsABookThroughANestedZipLocator() throws IOException {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        ByteArrayOutputStream nestedBytes = new ByteArrayOutputStream();
        try (ZipOutputStream nested = new ZipOutputStream(nestedBytes)) {
            nested.putNextEntry(new ZipEntry("folder/book.fb2"));
            nested.write("nested-content".getBytes(StandardCharsets.UTF_8));
            nested.closeEntry();
        }
        Path outer = archiveRoot.resolve("outer.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(outer))) {
            output.putNextEntry(new ZipEntry("inner.zip"));
            output.write(nestedBytes.toByteArray());
            output.closeEntry();
        }
        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        ArchivedBookContentService service = new ArchivedBookContentService(properties, new ArchiveService());
        BookFileEntity file = archivedFile(archiveRoot, "outer.zip",
                NestedArchiveLocator.encode(List.of("inner.zip", "folder/book.fb2")));
        file.setFileName("book.fb2");

        Path resolved = service.resolve(file);

        assertThat(resolved).hasContent("nested-content");
        assertThat(resolved.getFileName()).hasToString("book.fb2");
        try (var files = Files.list(resolved.getParent())) {
            assertThat(files).containsExactly(resolved);
        }
    }

    @Test
    void listsAndStreamsOnlyExactEntriesFromTheContainingPublicationArchive() throws IOException {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        ByteArrayOutputStream nestedBytes = new ByteArrayOutputStream();
        try (ZipOutputStream nested = new ZipOutputStream(nestedBytes)) {
            nested.putNextEntry(new ZipEntry("letter.html"));
            nested.write("html".getBytes(StandardCharsets.UTF_8));
            nested.closeEntry();
            nested.putNextEntry(new ZipEntry("img/00.gif"));
            nested.write("gif".getBytes(StandardCharsets.UTF_8));
            nested.closeEntry();
        }
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(archiveRoot.resolve("outer.zip")))) {
            output.putNextEntry(new ZipEntry("publication.zip"));
            output.write(nestedBytes.toByteArray());
            output.closeEntry();
        }
        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        ArchivedBookContentService service = new ArchivedBookContentService(properties, new ArchiveService());
        BookFileEntity file = archivedFile(archiveRoot, "outer.zip",
                NestedArchiveLocator.encode(List.of("publication.zip", "letter.html")));
        file.setFileName("letter.html");

        assertThat(service.listPublicationEntries(file))
                .extracting(ArchivedBookContentService.ArchivedEntry::name)
                .containsExactly("letter.html", "img/00.gif");
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        service.streamPublicationEntry(file, "img/00.gif", image);
        assertThat(image.toString(StandardCharsets.UTF_8)).isEqualTo("gif");
        assertThatThrownBy(() -> service.streamPublicationEntry(file, "img/missing.gif",
                new ByteArrayOutputStream())).isInstanceOf(IOException.class);
    }

    @Test
    void extractsThroughAMixedLegacyCyrillicNestedLocator() throws IOException {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        byte[] nested = ZipCharsetTestFixtures.bytes("windows-1251", false,
                UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Толстой.fb2", "nested-legacy-content"));
        ZipCharsetTestFixtures.write(archiveRoot.resolve("outer.zip"), "IBM866", false,
                UnicodeExtraFieldPolicy.NEVER,
                ZipCharsetTestFixtures.entry("Внутри.zip", nested));
        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        ArchivedBookContentService service = new ArchivedBookContentService(properties, new ArchiveService());
        BookFileEntity file = archivedFile(archiveRoot, "outer.zip",
                NestedArchiveLocator.encode(List.of("Внутри.zip", "Толстой.fb2")));
        file.setFileName("Толстой.fb2");

        Path resolved = service.resolve(file);

        assertThat(resolved).hasContent("nested-legacy-content");
        assertThat(resolved.getFileName()).hasToString("Толстой.fb2");
    }

    @Test
    void revalidatedResolveReportsAVanishedEntryEvenWhenTheCacheLooksFresh() throws IOException {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        Path archive = archiveRoot.resolve("fb2-1-100.zip");
        writeArchive(archive, "42.fb2", "<FictionBook>original</FictionBook>");

        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        ArchivedBookContentService service = new ArchivedBookContentService(properties, new ArchiveService());
        BookFileEntity file = archivedFile(archiveRoot, "fb2-1-100.zip", "42.fb2");

        service.resolve(file);

        // The archive is restored from a backup that no longer holds this entry, with its original
        // timestamp preserved (rsync -a / cp -p / tar -x) - so it is OLDER than the cached copy.
        writeArchive(archive, "99.fb2", "<FictionBook>replacement</FictionBook>");
        Files.setLastModifiedTime(archive, FileTime.from(Instant.now().minusSeconds(3600)));

        // The cached read path still serves the stale copy - that is the cache doing its job.
        assertThat(service.resolve(file)).hasContent("<FictionBook>original</FictionBook>");

        // The repair path must not trust it, or the orphan is never retired.
        assertThatThrownBy(() -> service.resolveRevalidated(file))
                .isInstanceOf(ArchiveEntryMissingException.class)
                .hasMessageContaining("42.fb2");
    }

    @Test
    void revalidatedResolveRefreshesContentThatWasReplacedUnderTheSameEntryName() throws IOException {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        Path archive = archiveRoot.resolve("fb2-1-100.zip");
        writeArchive(archive, "42.fb2", "<FictionBook>original</FictionBook>");

        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        ArchivedBookContentService service = new ArchivedBookContentService(properties, new ArchiveService());
        BookFileEntity file = archivedFile(archiveRoot, "fb2-1-100.zip", "42.fb2");

        service.resolve(file);
        writeArchive(archive, "42.fb2", "<FictionBook>replacement</FictionBook>");
        Files.setLastModifiedTime(archive, FileTime.from(Instant.now().minusSeconds(3600)));

        assertThat(service.resolveRevalidated(file)).hasContent("<FictionBook>replacement</FictionBook>");
    }

    private void writeArchive(Path archive, String entryName, String content) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private BookFileEntity archivedFile(Path archiveRoot, String archiveName, String entryName) {
        LibraryEntity library = LibraryEntity.builder()
                .id(7L)
                .inpxArchivePath(archiveRoot.toString())
                .build();
        return BookFileEntity.builder()
                .id(9L)
                .book(BookEntity.builder().library(library).build())
                .fileName(entryName)
                .fileSubPath("")
                .sourceArchive(archiveName)
                .sourceArchiveEntry(entryName)
                .build();
    }

    @Test
    void sharesOneCompletedExtractionAcrossConcurrentCallers() throws Exception {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        Path archive = archiveRoot.resolve("books.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("42.fb2"));
            output.write("<FictionBook>concurrent</FictionBook>".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        AppProperties properties = new AppProperties();
        properties.setPathConfig(tempDir.resolve("data").toString());
        ArchivedBookContentService service = new ArchivedBookContentService(properties, new ArchiveService());
        LibraryEntity library = LibraryEntity.builder()
                .id(7L)
                .inpxArchivePath(archiveRoot.toString())
                .build();
        BookFileEntity file = BookFileEntity.builder()
                .id(9L)
                .book(BookEntity.builder().library(library).build())
                .fileName("42.fb2")
                .fileSubPath("")
                .sourceArchive("books.zip")
                .sourceArchiveEntry("42.fb2")
                .build();
        List<Callable<Path>> calls = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            calls.add(() -> service.resolve(file));
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Path>> futures = executor.invokeAll(calls);
            List<Path> resolved = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }).toList();

            assertThat(resolved).containsOnly(resolved.getFirst());
            assertThat(resolved.getFirst()).hasContent("<FictionBook>concurrent</FictionBook>");
            try (var cachedFiles = Files.list(resolved.getFirst().getParent())) {
                assertThat(cachedFiles).hasSize(1);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
