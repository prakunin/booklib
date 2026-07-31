package org.booklore.service.reader;

import org.booklore.model.dto.response.CbxPageDimension;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.djvu.DjvuBookLocator;
import org.booklore.service.djvu.DjvuDocumentInfo;
import org.booklore.service.djvu.DjvuToolRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DjvuReaderServiceTest {

    private static final long BOOK_ID = 42L;

    private final DjvuBookLocator bookLocator = mock(DjvuBookLocator.class);
    private final DjvuToolRunner toolRunner = mock(DjvuToolRunner.class);
    private final ChapterCacheService chapterCacheService = mock(ChapterCacheService.class);

    private final DjvuReaderService service = new DjvuReaderService(bookLocator, toolRunner, chapterCacheService);

    @TempDir
    Path tempDir;

    private Path djvuFile;

    @BeforeEach
    void setUp() throws Exception {
        djvuFile = tempDir.resolve("scan.djvu");
        Files.writeString(djvuFile, "not really a djvu, only its path and mtime are used");

        when(bookLocator.locate(BOOK_ID, "DJVU")).thenReturn(djvuFile);

        when(toolRunner.probe(djvuFile)).thenReturn(new DjvuDocumentInfo(3, List.of(
                new DjvuDocumentInfo.PageSize(120, 160),
                new DjvuDocumentInfo.PageSize(200, 100),
                new DjvuDocumentInfo.PageSize(120, 160)), Map.of()));
    }

    @Test
    void servesTheDjvuType() {
        assertThat(service.supportedType()).isEqualTo(BookFileType.DJVU);
    }

    @Nested
    class Structure {

        @Test
        void listsOnePageNumberPerPage() {
            assertThat(service.getAvailablePages(BOOK_ID, "DJVU")).containsExactly(1, 2, 3);
        }

        @Test
        void labelsPagesByNumberBecauseDjvuPagesHaveNoNames() {
            assertThat(service.getPageInfo(BOOK_ID, "DJVU"))
                    .extracting("displayName")
                    .containsExactly("1", "2", "3");
        }

        @Test
        void reportsDimensionsFromTheDocumentWithoutRenderingAnything() {
            List<CbxPageDimension> dimensions = service.getPageDimensions(BOOK_ID, "DJVU");

            assertThat(dimensions).extracting(CbxPageDimension::getWidth).containsExactly(120, 200, 120);
            assertThat(dimensions).extracting(CbxPageDimension::isWide).containsExactly(false, true, false);
            verify(toolRunner, never()).renderPageAsJpeg(any(), anyInt(), anyInt(), any());
        }

        @Test
        void aPageWithoutAReportedSizeFallsBackRatherThanFailing() {
            when(toolRunner.probe(djvuFile)).thenReturn(new DjvuDocumentInfo(2, List.of(), Map.of()));

            assertThat(service.getPageDimensions(BOOK_ID, "DJVU"))
                    .extracting(CbxPageDimension::getWidth)
                    .containsExactly(0, 0);
        }

        @Test
        void theDocumentIsProbedOnceAndThenRemembered() {
            service.getAvailablePages(BOOK_ID, "DJVU");
            service.getPageDimensions(BOOK_ID, "DJVU");
            service.getPageInfo(BOOK_ID, "DJVU");

            verify(toolRunner, times(1)).probe(djvuFile);
        }
    }

    @Nested
    class StreamPage {

        @Test
        void rendersOnlyTheRequestedPage() throws Exception {
            Path cached = stubCache(2, false);

            service.streamPageImage(BOOK_ID, "DJVU", 2, OutputStream.nullOutputStream());

            verify(toolRunner).renderPageAsJpeg(eq(djvuFile), eq(2), anyInt(), any());
            verify(toolRunner, never()).renderPageAsJpeg(any(), eq(1), anyInt(), any());
            assertThat(cached).exists();
        }

        @Test
        void aCachedPageIsServedWithoutRunningTheDecoder() throws Exception {
            stubCache(2, true);

            service.streamPageImage(BOOK_ID, "DJVU", 2, OutputStream.nullOutputStream());

            verify(toolRunner, never()).renderPageAsJpeg(any(), anyInt(), anyInt(), any());
        }

        @Test
        void aPageBeyondTheDocumentIsRejectedBeforeAnythingIsRendered() throws Exception {
            stubCache(4, false);

            assertThatThrownBy(() -> service.streamPageImage(BOOK_ID, "DJVU", 4, OutputStream.nullOutputStream()))
                    .isInstanceOf(FileNotFoundException.class)
                    .hasMessageContaining("out of range");
            verify(toolRunner, never()).renderPageAsJpeg(any(), anyInt(), anyInt(), any());
        }

        @Test
        void pageZeroIsRejected() throws Exception {
            stubCache(1, false);

            assertThatThrownBy(() -> service.streamPageImage(BOOK_ID, "DJVU", 0, OutputStream.nullOutputStream()))
                    .isInstanceOf(FileNotFoundException.class);
        }

        private Path stubCache(int page, boolean alreadyCached) throws Exception {
            Path cacheDir = tempDir.resolve("cache");
            Files.createDirectories(cacheDir);
            Path cached = cacheDir.resolve("page_" + page + ".jpg");
            if (alreadyCached) {
                Files.writeString(cached, "cached jpeg bytes");
            }
            when(chapterCacheService.getCachedPage(anyString(), eq(page))).thenReturn(cached);
            when(chapterCacheService.hasPage(anyString(), eq(page))).thenReturn(alreadyCached);
            if (!alreadyCached) {
                org.mockito.Mockito.doAnswer(invocation -> {
                    Path target = invocation.getArgument(0);
                    ChapterCacheService.IOConsumer<OutputStream> writer = invocation.getArgument(1);
                    try (OutputStream out = Files.newOutputStream(target)) {
                        writer.accept(out);
                    }
                    return null;
                }).when(chapterCacheService).writeAtomically(any(), any());
            }
            return cached;
        }
    }

    @Test
    void anUnknownBookIsRejectedWithoutTouchingTheDecoder() {
        when(bookLocator.locate(99L, "DJVU")).thenThrow(new IllegalStateException("book not found"));

        assertThatThrownBy(() -> service.getAvailablePages(99L, "DJVU")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(toolRunner);
    }
}
