package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import org.booklore.service.djvu.DjvuDocumentInfo;
import org.booklore.service.djvu.DjvuToolException;
import org.booklore.service.djvu.DjvuToolRunner;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DjvuMetadataExtractorTest {

    private final DjvuToolRunner toolRunner = mock(DjvuToolRunner.class);
    private final DjvuMetadataExtractor extractor = new DjvuMetadataExtractor(toolRunner);

    private final File file = new File("/books/scan.djvu");

    private void probeReturns(Map<String, String> metadata) {
        when(toolRunner.probe(any())).thenReturn(new DjvuDocumentInfo(3, List.of(), metadata));
    }

    @Nested
    class ExtractMetadata {

        @Test
        void mapsEmbeddedMetadataWhenPresent() {
            probeReturns(Map.of("Title", "Radio Magazine", "Author", "A. Popov",
                    "Publisher", "Svyaz", "Language", "ru"));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getTitle()).isEqualTo("Radio Magazine");
            assertThat(metadata.getAuthors()).containsExactly("A. Popov");
            assertThat(metadata.getPublisher()).isEqualTo("Svyaz");
            assertThat(metadata.getLanguage()).isEqualTo("ru");
        }

        @Test
        void keysAreMatchedRegardlessOfCase() {
            probeReturns(Map.of("TITLE", "Shouted", "author", "Quiet"));

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getTitle()).isEqualTo("Shouted");
            assertThat(metadata.getAuthors()).containsExactly("Quiet");
        }

        @Test
        void aBareYearBecomesTheFirstOfJanuary() {
            probeReturns(Map.of("Year", "1972"));

            assertThat(extractor.extractMetadata(file).getPublishedDate())
                    .isEqualTo(LocalDate.of(1972, 1, 1));
        }

        @Test
        void anIsoDateIsKeptExactly() {
            probeReturns(Map.of("Date", "1972-10-15"));

            assertThat(extractor.extractMetadata(file).getPublishedDate())
                    .isEqualTo(LocalDate.of(1972, 10, 15));
        }

        @Test
        void anUnparseableDateIsLeftBlankRatherThanGuessed() {
            probeReturns(Map.of("Year", "not a year"));

            assertThat(extractor.extractMetadata(file).getPublishedDate()).isNull();
        }

        @Test
        void semicolonsSeparateAuthorsButCommasDoNot() {
            probeReturns(Map.of("Author", "Popov, A.; Ivanov, B."));

            assertThat(extractor.extractMetadata(file).getAuthors())
                    .containsExactly("Popov, A.", "Ivanov, B.");
        }

        @Test
        void aDocumentWithoutMetadataYieldsBlanksForTheFilenameBaselineToFill() {
            probeReturns(Map.of());

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getTitle()).isNull();
            assertThat(metadata.getAuthors()).isEmpty();
            assertThat(metadata.getPublishedDate()).isNull();
        }

        @Test
        void aToolFailureReportsNoMetadataRatherThanPropagating() {
            when(toolRunner.probe(any())).thenThrow(new DjvuToolException("no djvused"));

            assertThat(extractor.extractMetadata(file)).isNull();
        }
    }

    @Nested
    class ExtractCover {

        @Test
        void theCoverIsTheFirstPage() {
            doAnswer(invocation -> {
                OutputStream out = invocation.getArgument(3);
                out.write(new byte[]{1, 2, 3});
                return null;
            }).when(toolRunner).renderPageAsJpeg(any(), eq(1), anyInt(), any());

            assertThat(extractor.extractCover(file)).containsExactly(1, 2, 3);
            verify(toolRunner).renderPageAsJpeg(any(), eq(1), anyInt(), any());
        }

        @Test
        void aFailedRenderIsAmbiguousAndThrowsRatherThanReportingNoCover() {
            // null would mean "read the file, it genuinely has no cover", which a DjVu document -
            // every one of which has a page 1 - can never be.
            doThrow(new DjvuToolException("boom"))
                    .when(toolRunner).renderPageAsJpeg(any(), anyInt(), anyInt(), any());

            assertThatThrownBy(() -> extractor.extractCover(file))
                    .isInstanceOf(CoverExtractionException.class);
        }
    }
}
