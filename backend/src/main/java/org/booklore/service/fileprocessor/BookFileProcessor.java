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
     * An implementation must have <strong>no side effects</strong>: it must not write the cover to
     * disk and must not mutate {@code bookEntity}. That is what lets a caller sequence the write
     * however it needs to - notably {@code BookCoverService#tryGenerateMissingInpxCover}, which
     * claims the book's cover in the database before writing anything, so that losing the claim
     * costs only discarded bytes rather than an overwritten file. {@code generateCover} is then this
     * read followed by a write, not a second way to read.
     * <p>
     * This is deliberately abstract rather than defaulted. It used to default to delegating to
     * {@code generateCover} and reporting a cover that was already on disk, which meant a processor
     * that had not been taught to read without writing silently inherited a value the lazy path had
     * to detect and refuse at runtime - after that path's whole reason to exist had already been
     * violated. Forcing every processor to answer for itself turns that landmine into a compile
     * error: a new format cannot be added without deciding what its three outcomes are.
     * <p>
     * The three outcomes are not interchangeable. {@code NO_COVER_FOUND} is a permanent fact about
     * the file and callers may persist it as one, so only return it having actually read the source
     * through to the end and found nothing; anything ambiguous - an IO error, a parse failure, a
     * format whose reader cannot distinguish the two - is {@code READ_FAILED}. Reporting a failure
     * as a clean miss lets a caller record a transient problem as a permanent verdict.
     */
    CoverExtraction extractCover(BookEntity bookEntity, BookFileEntity bookFile);
}
