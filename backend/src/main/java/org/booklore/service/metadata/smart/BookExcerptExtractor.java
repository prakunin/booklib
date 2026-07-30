package org.booklore.service.metadata.smart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Optional;

/**
 * Pulls the opening prose out of the book file for the enrichment prompt.
 * <p>
 * The point the whole feature turned on: a Flibusta FB2 can have an empty {@code title-info} yet
 * print the author, title, series and publisher in plain text on its first page. Feeding that text
 * to the agent lets it identify the book from its own reading — no web search — which is exactly
 * what the cheap default mode needs. Only FB2 is handled for now; other formats return empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookExcerptExtractor {

    private final Fb2MetadataExtractor fb2MetadataExtractor;
    private final BookRepository bookRepository;
    private final ArchivedBookContentService archivedBookContentService;

    public Optional<String> openingText(Book book, int maxChars) {
        BookFile primaryFile = book.getPrimaryFile();
        if (primaryFile == null || primaryFile.getBookType() != BookFileType.FB2) {
            return Optional.empty();
        }
        File file = resolveFb2File(book, primaryFile);
        if (file == null || !file.isFile()) {
            log.debug("Book {} FB2 source not on disk, no excerpt", book.getId());
            return Optional.empty();
        }
        return fb2MetadataExtractor.extractOpeningText(file, maxChars);
    }

    /**
     * INPX books carry a {@code sourceArchive}: their DTO {@code filePath} is a library-relative path
     * that never exists on disk, because the FB2 bytes live inside a ZIP and are only materialised in
     * the extraction cache on demand. Resolving through {@link ArchivedBookContentService} is the only
     * way to reach them — reading {@code filePath} directly is exactly why the excerpt used to come
     * back empty for the whole INPX library. A plain library file keeps the cheap direct path.
     */
    private File resolveFb2File(Book book, BookFile primaryFile) {
        if (StringUtils.isNotBlank(primaryFile.getSourceArchive())) {
            return resolveArchivedFb2File(book.getId());
        }
        String filePath = primaryFile.getFilePath();
        return StringUtils.isBlank(filePath) ? null : new File(filePath);
    }

    private File resolveArchivedFb2File(Long bookId) {
        if (bookId == null) {
            return null;
        }
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId).orElse(null);
        if (bookEntity == null) {
            return null;
        }
        BookFileEntity primaryFile = bookEntity.getPrimaryBookFile();
        if (primaryFile == null || !primaryFile.isArchivedSource()) {
            return null;
        }
        try {
            return archivedBookContentService.resolve(primaryFile).toFile();
        } catch (Exception e) {
            log.warn("Book {}: could not resolve archived FB2 for excerpt: {}", bookId, e.getMessage());
            return null;
        }
    }
}
