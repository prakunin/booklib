package org.booklore.service.djvu;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Resolves a book id to a DjVu file the decoder can actually open.
 * <p>
 * "Actually open" is the whole point of this class. A DjVu book need not be a file in a library
 * folder: most of them arrive as entries inside INPX archives, where nothing exists on disk until
 * the entry is extracted. Resolving such a book to its library path yields a path that cannot exist,
 * which is what a reader sees as a {@code NoSuchFileException} on a name it never wrote.
 * {@link ArchivedBookContentService#resolve} is the codebase's answer to that, and it covers both
 * cases - it hands a plain file straight back - so everything above here can stay ignorant of which
 * kind of book it has.
 * <p>
 * Shared by the reader and the rendition so the two cannot disagree about which file a book means -
 * a disagreement that would show as a rendition of one file being served for another, which is
 * exactly the kind of thing nobody notices until a page is wrong.
 */
@Component
@RequiredArgsConstructor
public class DjvuBookLocator {

    private final BookRepository bookRepository;
    private final ArchivedBookContentService archivedBookContentService;

    /**
     * @param bookType the optional alternative-format parameter the reader endpoints carry; when
     *                 absent the book's primary file is used
     */
    public Path locate(Long bookId, String bookType) {
        BookEntity book = bookRepository.findByIdForStreaming(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        return archivedBookContentService.resolve(bookFile(book, bookType));
    }

    private BookFileEntity bookFile(BookEntity book, String bookType) {
        if (bookType != null) {
            BookFileType requestedType = BookFileType.fromName(bookType)
                    .orElseThrow(() -> ApiError.INVALID_INPUT.createException("Invalid book type: " + bookType));
            return book.getBookFiles().stream()
                    .filter(file -> file.getBookType() == requestedType)
                    .findFirst()
                    .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException(
                            "No file of type " + bookType + " found for book"));
        }
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null) {
            throw ApiError.FILE_NOT_FOUND.createException("Book " + book.getId() + " has no readable file");
        }
        return primaryFile;
    }
}
