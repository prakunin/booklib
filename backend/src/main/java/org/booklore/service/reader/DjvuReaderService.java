package org.booklore.service.reader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.response.CbxPageDimension;
import org.booklore.model.dto.response.CbxPageInfo;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.service.djvu.DjvuDocumentInfo;
import org.booklore.service.djvu.DjvuToolRunner;
import org.booklore.util.FileUtils;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * Serves DjVu documents to the page reader.
 * <p>
 * Pages are rendered <strong>one at a time, on demand</strong>, which is the whole difference
 * between this and {@code PdfReaderService}: that one renders every page of a document up front,
 * which is affordable for a PDF and is not for a 600-page scan the reader may close after one page.
 * A rendered page lands in the same on-disk cache the comic reader reads from, so the second visit
 * to a page costs a file copy, and a small window of following pages is rendered in the background
 * so that turning the page is not a render.
 * <p>
 * Page sizes come from the document's annotations rather than from a rendered image, so the reader
 * can lay out the whole book without decoding any of it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DjvuReaderService implements PageImageSource {

    private static final int MAX_CACHE_ENTRIES = 50;

    /**
     * How many pages ahead of the one being served are rendered in the background. Two covers a
     * page turn and a two-page spread without turning a browse into a full-document render.
     */
    private static final int READ_AHEAD_PAGES = 2;

    /**
     * Longest edge of a rendered page. A guard against scans whose natural size is tens of
     * megapixels, not a quality setting - it is well above what any reader viewport shows.
     */
    private static final int MAX_PAGE_EDGE_PIXELS = 2400;

    private final BookRepository bookRepository;
    private final DjvuToolRunner toolRunner;
    private final ChapterCacheService chapterCacheService;

    private final Cache<String, CachedDocument> documentCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /** Single-threaded so read-ahead renders never compete with the page the reader is waiting for. */
    private final ExecutorService readAheadExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "djvu-read-ahead");
        thread.setDaemon(true);
        return thread;
    });

    private final java.util.Set<String> readAheadSubmitted = ConcurrentHashMap.newKeySet();

    private record CachedDocument(DjvuDocumentInfo info, long lastModified) {
    }

    @Override
    public BookFileType supportedType() {
        return BookFileType.DJVU;
    }

    @Override
    public List<Integer> getAvailablePages(Long bookId, String bookType) {
        CachedDocument document = document(bookId, bookType);
        return IntStream.rangeClosed(1, document.info().pageCount()).boxed().toList();
    }

    /**
     * DjVu pages carry no names - unlike comic archive entries, which are files - so the number is
     * the only honest label.
     */
    @Override
    public List<CbxPageInfo> getPageInfo(Long bookId, String bookType) {
        return getAvailablePages(bookId, bookType).stream()
                .map(page -> CbxPageInfo.builder()
                        .pageNumber(page)
                        .displayName(String.valueOf(page))
                        .build())
                .toList();
    }

    @Override
    public List<CbxPageDimension> getPageDimensions(Long bookId, String bookType) {
        CachedDocument document = document(bookId, bookType);
        List<DjvuDocumentInfo.PageSize> sizes = document.info().pageSizes();

        List<CbxPageDimension> dimensions = new ArrayList<>(document.info().pageCount());
        for (int page = 1; page <= document.info().pageCount(); page++) {
            DjvuDocumentInfo.PageSize size = page <= sizes.size() ? sizes.get(page - 1) : null;
            int width = size == null ? 0 : size.width();
            int height = size == null ? 0 : size.height();
            dimensions.add(CbxPageDimension.builder()
                    .pageNumber(page)
                    .width(width)
                    .height(height)
                    .wide(width > height)
                    .build());
        }
        return dimensions;
    }

    @Override
    public void streamPageImage(Long bookId, String bookType, int page, OutputStream outputStream) throws IOException {
        Path path = bookPath(bookId, bookType);
        CachedDocument document = document(bookId, bookType);
        if (page < 1 || page > document.info().pageCount()) {
            throw new FileNotFoundException("Page " + page + " out of range [1-" + document.info().pageCount() + "]");
        }

        String cacheKey = cacheKey(bookId, document.lastModified());
        Path cached = renderIfAbsent(path, cacheKey, page);
        Files.copy(cached, outputStream);

        submitReadAhead(path, cacheKey, page, document.info().pageCount());
    }

    /**
     * Renders the page into the disk cache unless it is already there, and returns its path. The
     * write is atomic, so a process killed mid-render never leaves a truncated page behind to be
     * served as a real one.
     */
    private Path renderIfAbsent(Path source, String cacheKey, int page) throws IOException {
        Path target = chapterCacheService.getCachedPage(cacheKey, page);
        if (chapterCacheService.hasPage(cacheKey, page)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        chapterCacheService.writeAtomically(target,
                out -> toolRunner.renderPageAsJpeg(source, page, MAX_PAGE_EDGE_PIXELS, out));
        return target;
    }

    private void submitReadAhead(Path source, String cacheKey, int servedPage, int pageCount) {
        for (int page = servedPage + 1; page <= Math.min(servedPage + READ_AHEAD_PAGES, pageCount); page++) {
            int target = page;
            String submissionKey = cacheKey + ":" + target;
            if (chapterCacheService.hasPage(cacheKey, target) || !readAheadSubmitted.add(submissionKey)) {
                continue;
            }
            readAheadExecutor.submit(() -> {
                try {
                    renderIfAbsent(source, cacheKey, target);
                } catch (IOException | RuntimeException e) {
                    // Read-ahead is an optimisation; a failure here must not be visible to anyone.
                    // The page is simply rendered on demand when it is actually asked for.
                    log.debug("Read-ahead render of page {} failed: {}", target, e.getMessage());
                    readAheadSubmitted.remove(submissionKey);
                }
            });
        }
    }

    private CachedDocument document(Long bookId, String bookType) {
        Path path = bookPath(bookId, bookType);
        long lastModified = lastModified(path);
        String key = path.toString();

        CachedDocument cached = documentCache.getIfPresent(key);
        if (cached != null && cached.lastModified() == lastModified) {
            return cached;
        }

        CachedDocument probed = new CachedDocument(toolRunner.probe(path), lastModified);
        documentCache.put(key, probed);
        // The file changed, so anything rendered from the old bytes is stale. Keying the page cache
        // on the modification time is what makes that automatic; this only reclaims the space.
        chapterCacheService.cleanupStaleCacheDirs(cacheKey(bookId, lastModified));
        return probed;
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String cacheKey(Long bookId, long lastModified) {
        return bookId + "_" + BookFileType.DJVU.name() + "_" + lastModified;
    }

    private Path bookPath(Long bookId, String bookType) {
        BookEntity book = bookRepository.findByIdForStreaming(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        if (bookType != null) {
            BookFileType requestedType = BookFileType.fromName(bookType)
                    .orElseThrow(() -> ApiError.INVALID_INPUT.createException("Invalid book type: " + bookType));
            return book.getBookFiles().stream()
                    .filter(file -> file.getBookType() == requestedType)
                    .findFirst()
                    .map(BookFileEntity::getFullFilePath)
                    .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException(
                            "No file of type " + bookType + " found for book"));
        }
        return FileUtils.getBookFullPath(book);
    }
}
