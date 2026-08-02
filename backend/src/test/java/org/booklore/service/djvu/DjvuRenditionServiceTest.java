package org.booklore.service.djvu;

import org.booklore.config.AppProperties;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DjvuRenditionServiceTest {

    private static final long BOOK_ID = 42L;

    private final AppProperties appProperties = mock(AppProperties.class);
    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final DjvuToolRunner toolRunner = mock(DjvuToolRunner.class);
    private final DjvuPdfWriter pdfWriter = mock(DjvuPdfWriter.class);

    private DjvuRenditionService service;

    @TempDir
    Path tempDir;

    private Path source;

    @BeforeEach
    void setUp() throws IOException {
        source = tempDir.resolve("scan.djvu");
        Files.writeString(source, "only the path and mtime matter here");

        when(appProperties.getPathConfig()).thenReturn(tempDir.resolve("data").toString());
        settings(true, 2048);
        lenient().when(toolRunner.isAvailable()).thenReturn(true);
        lenient().when(toolRunner.probe(any())).thenReturn(new DjvuDocumentInfo(2, List.of(), Map.of()));

        service = new DjvuRenditionService(appProperties, appSettingService, toolRunner, pdfWriter);
    }

    private void settings(boolean enabled, Integer cacheMb) {
        AppSettings appSettings = AppSettings.builder()
                .djvuPdfRenditionEnabled(enabled)
                .djvuRenditionCacheSizeInMb(cacheMb)
                .build();
        lenient().when(appSettingService.getAppSettings()).thenReturn(appSettings);
    }

    /** Makes the mocked writer actually produce a file, so the service's own bookkeeping is exercised. */
    private void writerProduces(int sizeBytes) {
        doAnswer(invocation -> {
            Path target = invocation.getArgument(2);
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[sizeBytes]);
            return null;
        }).when(pdfWriter).write(any(), any(), any(), any());
    }

    private void awaitRendition() {
        await().atMost(5, TimeUnit.SECONDS).until(() -> service.hasRendition(BOOK_ID, source));
    }

    @Nested
    class Building {

        @Test
        void buildsTheRenditionInTheBackground() {
            writerProduces(16);

            service.requestRendition(BOOK_ID, source);

            awaitRendition();
            assertThat(service.renditionPath(BOOK_ID, source)).isPresent();
        }

        @Test
        void doesNothingWhenTheFeatureIsSwitchedOff() {
            settings(false, 2048);

            service.requestRendition(BOOK_ID, source);

            verifyNoInteractions(pdfWriter);
            assertThat(service.hasRendition(BOOK_ID, source)).isFalse();
        }

        @Test
        void doesNothingWhenTheDecoderIsAbsent() {
            when(toolRunner.isAvailable()).thenReturn(false);

            service.requestRendition(BOOK_ID, source);

            verifyNoInteractions(pdfWriter);
        }

        @Test
        void aSecondRequestDoesNotRebuildWhatIsAlreadyThere() {
            writerProduces(16);
            service.requestRendition(BOOK_ID, source);
            awaitRendition();

            service.requestRendition(BOOK_ID, source);

            verify(pdfWriter).write(any(), any(), any(), any());
        }

        @Test
        void aFailedBuildIsSwallowedBecauseTheBookIsReadableWithoutIt() {
            doAnswer(_ -> {
                throw new DjvuToolException("boom");
            }).when(pdfWriter).write(any(), any(), any(), any());

            service.requestRendition(BOOK_ID, source);

            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(pdfWriter).write(any(), any(), any(), any()));
            assertThat(service.hasRendition(BOOK_ID, source)).isFalse();
        }

        @Test
        void aPartialFileIsNeverLeftWhereTheReaderWouldFindIt() {
            doAnswer(invocation -> {
                Path target = invocation.getArgument(2);
                Files.createDirectories(target.getParent());
                Files.write(target, new byte[8]);
                throw new DjvuToolException("died after writing part of it");
            }).when(pdfWriter).write(any(), any(), any(), any());

            service.requestRendition(BOOK_ID, source);

            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(pdfWriter).write(any(), any(), any(), any()));
            assertThat(service.hasRendition(BOOK_ID, source)).isFalse();
        }
    }

    @Nested
    class Staleness {

        @Test
        void aChangedSourceHasNoRendition() throws IOException {
            writerProduces(16);
            service.requestRendition(BOOK_ID, source);
            awaitRendition();

            // The name carries the source's modification time, so a changed source cannot match an
            // existing rendition. There is no invalidation step that could be forgotten.
            Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(
                    Files.getLastModifiedTime(source).toMillis() + 10_000));

            assertThat(service.hasRendition(BOOK_ID, source)).isFalse();
        }

        @Test
        void rebuildingAfterAChangeRemovesTheSupersededFile() throws IOException {
            writerProduces(16);
            service.requestRendition(BOOK_ID, source);
            awaitRendition();

            Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(
                    Files.getLastModifiedTime(source).toMillis() + 10_000));
            service.requestRendition(BOOK_ID, source);
            awaitRendition();

            assertThat(renditionCount()).isEqualTo(1);
        }
    }

    @Nested
    class Eviction {

        @Test
        void evictsUntilTheCacheIsBackUnderItsLimit() throws IOException {
            settings(true, 1);
            // Three 512 KB renditions against a 1 MB ceiling: the oldest has to go.
            seedRendition(1L, 512 * 1024);
            seedRendition(2L, 512 * 1024);
            writerProduces(512 * 1024);

            service.requestRendition(BOOK_ID, source);
            awaitRendition();

            assertThat(renditionCount()).isLessThan(3);
        }

        @Test
        void keepsEverythingWhenNoLimitIsSet() throws IOException {
            settings(true, 0);
            seedRendition(1L, 4096);
            writerProduces(4096);

            service.requestRendition(BOOK_ID, source);
            awaitRendition();

            assertThat(renditionCount()).isEqualTo(2);
        }
    }

    private void seedRendition(long bookId, int sizeBytes) throws IOException {
        Path dir = Path.of(appProperties.getPathConfig(), "cache", "djvu-renditions");
        Files.createDirectories(dir);
        Files.write(dir.resolve(bookId + "_1.pdf"), new byte[sizeBytes]);
    }

    private long renditionCount() throws IOException {
        Path dir = Path.of(appProperties.getPathConfig(), "cache", "djvu-renditions");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var paths = Files.list(dir)) {
            AtomicInteger count = new AtomicInteger();
            paths.filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .forEach(_ -> count.incrementAndGet());
            return count.get();
        }
    }
}
