package org.booklore.service.reader;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the {@link PageImageSource} for a request.
 * <p>
 * The reader endpoints take an optional {@code bookType} - the "alternative format" parameter that
 * lets a reader open one of a book's other files. When it is given it decides the source; when it
 * is absent the book's primary file does. Implementations are collected from the context, so
 * supporting another page-based format means adding a bean, not editing this class.
 */
@Slf4j
@Component
public class PageImageSourceResolver {

    private final BookRepository bookRepository;
    private final Map<BookFileType, PageImageSource> sources = new EnumMap<>(BookFileType.class);

    public PageImageSourceResolver(BookRepository bookRepository, List<PageImageSource> pageImageSources) {
        this.bookRepository = bookRepository;
        pageImageSources.forEach(source -> sources.put(source.supportedType(), source));
        log.debug("Page image sources registered for {}", sources.keySet());
    }

    public PageImageSource resolve(Long bookId, String bookType) {
        BookFileType type = bookType != null ? parse(bookType) : primaryType(bookId);
        PageImageSource source = sources.get(type);
        if (source == null) {
            throw ApiError.INVALID_INPUT.createException("Book type " + type + " is not read as page images");
        }
        return source;
    }

    private BookFileType parse(String bookType) {
        return BookFileType.fromName(bookType)
                .orElseThrow(() -> ApiError.INVALID_INPUT.createException("Invalid book type: " + bookType));
    }

    private BookFileType primaryType(Long bookId) {
        BookEntity book = bookRepository.findByIdForStreaming(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null || primaryFile.getBookType() == null) {
            throw ApiError.FILE_NOT_FOUND.createException("Book " + bookId + " has no readable primary file");
        }
        return primaryFile.getBookType();
    }
}
