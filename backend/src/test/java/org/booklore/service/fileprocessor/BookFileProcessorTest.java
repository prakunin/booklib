package org.booklore.service.fileprocessor;

import org.booklore.model.CoverExtraction;
import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.CoverProbeOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the default {@link BookFileProcessor#extractCover} implementation to the safe outcome. This
 * default is what any processor gets for free the moment it's wired into the archived-INPX lazy
 * cover path without proving it can tell a clean miss apart from a read failure - so it must never
 * be "simplified" back to collapsing every failure into {@link CoverProbeOutcome#NO_COVER_FOUND}.
 */
class BookFileProcessorTest {

    /**
     * Stands in for any processor - current or future - that hasn't overridden extractCover. Its
     * generateCover always returns {@code false}, which is exactly the ambiguous signal (could be a
     * read failure, a parse failure, or a genuine clean miss) that the default must not collapse
     * into a permanently-persistable "no cover" answer.
     */
    private static class NonExtractingProcessor implements BookFileProcessor {
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

    @Nested
    class DefaultExtractCover {

        @Test
        void reportsReadFailedNotNoCoverFoundWhenGenerateCoverFails() {
            NonExtractingProcessor processor = new NonExtractingProcessor();
            BookEntity book = BookEntity.builder().id(1L).build();
            BookFileEntity bookFile = BookFileEntity.builder().build();

            CoverExtraction extraction = processor.extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
            assertThat(processor.generateCoverCalled).isTrue();
        }

        /**
         * The legacy default writes the image itself, so it has no bytes to hand back. Reporting
         * that honestly is what lets the lazy path recognise it cannot claim-before-write and bow
         * out, instead of writing after the fact.
         */
        @Test
        void reportsCoverWrittenInPlaceWithoutDataWhenGenerateCoverSucceeds() {
            NonExtractingProcessor processor = new NonExtractingProcessor() {
                @Override
                public boolean generateCover(BookEntity bookEntity) {
                    generateCoverCalled = true;
                    return true;
                }
            };
            BookEntity book = BookEntity.builder().id(1L).build();
            BookFileEntity bookFile = BookFileEntity.builder().build();

            CoverExtraction extraction = processor.extractCover(book, bookFile);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.hasData()).isFalse();
        }
    }
}
