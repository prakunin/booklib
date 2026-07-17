package org.booklore.service.fileprocessor;

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
     * Probes for a cover, distinguishing a completed read that found nothing ({@link
     * CoverProbeOutcome#NO_COVER_FOUND}) from a failure to read the source at all ({@link
     * CoverProbeOutcome#READ_FAILED}). Callers that persist a "no cover" marker must use this
     * instead of {@link #generateCover(BookEntity, BookFileEntity)} so a transient read failure is
     * never recorded as a permanent answer.
     * <p>
     * Defaults to {@link CoverProbeOutcome#READ_FAILED} on anything short of a proven cover: {@link
     * #generateCover(BookEntity, BookFileEntity)} returns {@code false} for read failures, parse
     * failures and write failures alike, so collapsing that into {@code NO_COVER_FOUND} here would
     * let any archived format - present or future - persist a transient failure as a permanent "no
     * cover". Only override this to return {@code NO_COVER_FOUND} once a processor can actually
     * prove a clean miss (see {@link org.booklore.service.fileprocessor.Fb2Processor}).
     */
    default CoverProbeOutcome probeCover(BookEntity bookEntity, BookFileEntity bookFile) {
        return generateCover(bookEntity, bookFile) ? CoverProbeOutcome.COVER_FOUND : CoverProbeOutcome.READ_FAILED;
    }
}
