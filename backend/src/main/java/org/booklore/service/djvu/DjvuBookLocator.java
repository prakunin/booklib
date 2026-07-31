package org.booklore.service.djvu;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.util.FileUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Resolves a book id to the DjVu file on disk.
 * <p>
 * Shared by the reader and the rendition so the two cannot disagree about which file a book means -
 * a disagreement that would show as a rendition of one file being served for another, which is
 * exactly the kind of thing nobody notices until a page is wrong.
 */
@Component
@RequiredArgsConstructor
public class DjvuBookLocator {

    private final BookRepository bookRepository;

    /**
     * @param bookType the optional alternative-format parameter the reader endpoints carry; when
     *                 absent the book's primary file is used
     */
    public Path locate(Long bookId, String bookType) {
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
