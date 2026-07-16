package org.booklore.service.inpx;

import org.booklore.config.AppProperties;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

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
        ArchivedBookContentService service = new ArchivedBookContentService(properties);

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

        assertThat(first).isEqualTo(second);
        assertThat(first).hasContent("<FictionBook>cached</FictionBook>");
        assertThat(first).startsWith(tempDir.resolve("data/cache/inpx/7/9"));
        assertThat(archive).exists();
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
        ArchivedBookContentService service = new ArchivedBookContentService(properties);
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
