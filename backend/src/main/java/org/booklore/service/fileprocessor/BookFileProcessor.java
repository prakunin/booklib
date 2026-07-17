package org.booklore.service.fileprocessor;

import org.booklore.model.CoverExtraction;
import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.CoverProbeOutcome;

import java.util.List;

import org.booklore.model.entity.BookFileEntity;

public interface BookFileProcessor {
    List<BookFileType> getSupportedTypes();

    FileProcessResult processFile(LibraryFile libraryFile);

    boolean generateCover(BookEntity bookEntity);

    default boolean generateCover(BookEntity bookEntity, BookFileEntity bookFile) {
        return generateCover(bookEntity);
    }

    default boolean generateAudiobookCover(BookEntity bookEntity) {
        return generateCover(bookEntity);
    }

    /**
     * Reads the cover out of the book's source file and hands back the bytes, distinguishing a
     * completed read that found nothing ({@link CoverProbeOutcome#NO_COVER_FOUND}) from a failure
     * to read the source at all ({@link CoverProbeOutcome#READ_FAILED}).
     * <p>
     * An override must have <strong>no side effects</strong>: it must not write the cover to disk
     * and must not mutate {@code bookEntity}. That is what lets a caller sequence the write however
     * it needs to - notably {@code BookCoverService#tryGenerateMissingInpxCover}, which claims the
     * book's cover in the database before writing anything, so that losing the claim costs only
     * discarded bytes rather than an overwritten file.
     * <p>
     * The default cannot honour that contract, because a processor that has not overridden this has
     * only {@link #generateCover(BookEntity, BookFileEntity)} - which writes the image itself and
     * reports a bare boolean. So the default delegates and reports {@link
     * CoverExtraction#writtenInPlace()}: a cover, but already on disk and with no bytes to return.
     * Callers that need claim-before-write must refuse such a result rather than write after the
     * fact.
     * <p>
     * Crucially the default never reports {@code NO_COVER_FOUND}. {@code generateCover} returns
     * {@code false} for read failures, parse failures and write failures alike, so collapsing that
     * into a clean miss would let any archived format - present or future - persist a transient
     * failure as a permanent "no cover". Only override this to return {@code NO_COVER_FOUND} once a
     * processor can actually prove the source has none (see {@link Fb2Processor}).
     */
    default CoverExtraction extractCover(BookEntity bookEntity, BookFileEntity bookFile) {
        return generateCover(bookEntity, bookFile) ? CoverExtraction.writtenInPlace() : CoverExtraction.readFailed();
    }
}
