package org.booklore.service.fileprocessor;

import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.CoverProbeOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the default {@link BookFileProcessor#probeCover} implementation to the safe outcome. This
 * default is what any processor gets for free the moment it's wired into the archived-INPX lazy
 * cover path without proving it can tell a clean miss apart from a read failure - so it must never
 * be "simplified" back to collapsing every failure into {@link CoverProbeOutcome#NO_COVER_FOUND}.
 */
class BookFileProcessorTest {

    /**
     * Stands in for any processor - current or future - that hasn't overridden probeCover. Its
     * generateCover always returns {@code false}, which is exactly the ambiguous signal (could be a
     * read failure, a parse failure, or a genuine clean miss) that the default must not collapse
     * into a permanently-persistable "no cover" answer.
     */
    private static class NonProbingProcessor implements BookFileProcessor {
        boolean generateCoverCalled = false;

        @Override
        public List<BookFileType> getSupportedTypes() {
            return List.of(BookFileType.EPUB);
        }

        @Override
        public FileProcessResult processFile(LibraryFile libraryFile) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public boolean generateCover(BookEntity bookEntity) {
            generateCoverCalled = true;
            return false;
        }
    }

    @Test
    void defaultProbeCoverReportsReadFailedNotNoCoverFoundWhenGenerateCoverFails() {
        NonProbingProcessor processor = new NonProbingProcessor();
        BookEntity book = BookEntity.builder().id(1L).build();
        BookFileEntity bookFile = BookFileEntity.builder().build();

        CoverProbeOutcome outcome = processor.probeCover(book, bookFile);

        assertThat(outcome).isEqualTo(CoverProbeOutcome.READ_FAILED);
        assertThat(processor.generateCoverCalled).isTrue();
    }

    @Test
    void defaultProbeCoverReportsCoverFoundWhenGenerateCoverSucceeds() {
        NonProbingProcessor processor = new NonProbingProcessor() {
            @Override
            public boolean generateCover(BookEntity bookEntity) {
                generateCoverCalled = true;
                return true;
            }
        };
        BookEntity book = BookEntity.builder().id(1L).build();
        BookFileEntity bookFile = BookFileEntity.builder().build();

        CoverProbeOutcome outcome = processor.probeCover(book, bookFile);

        assertThat(outcome).isEqualTo(CoverProbeOutcome.COVER_FOUND);
    }
}
