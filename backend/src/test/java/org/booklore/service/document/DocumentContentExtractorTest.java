package org.booklore.service.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.booklore.model.document.DocumentBlock;
import org.booklore.model.document.DocumentContent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentContentExtractorTest {

    private final DocumentContentExtractor extractor = new DocumentContentExtractor();

    @TempDir
    Path tempDir;

    private Path writeDocx(String name, DocxBuilder builder) throws Exception {
        Path file = tempDir.resolve(name);
        try (XWPFDocument doc = new XWPFDocument()) {
            builder.build(doc);
            try (OutputStream out = Files.newOutputStream(file)) {
                doc.write(out);
            }
        }
        return file;
    }

    @FunctionalInterface
    private interface DocxBuilder {
        void build(XWPFDocument doc);
    }

    private void paragraph(XWPFDocument doc, String style, String text) {
        XWPFParagraph p = doc.createParagraph();
        if (style != null) {
            p.setStyle(style);
        }
        if (text != null) {
            p.createRun().setText(text);
        }
    }

    @Nested
    class Ordinals {

        @Test
        void skippedParagraphsLeaveAGapRatherThanShiftingWhatFollows() throws Exception {
            Path file = writeDocx("gaps.docx", doc -> {
                paragraph(doc, "Heading1", "Chapter One");
                paragraph(doc, null, null);            // blank, dropped
                paragraph(doc, null, "   ");           // whitespace only, dropped
                paragraph(doc, null, "Body text");
            });

            DocumentContent content = extractor.extract(file.toFile());

            // Source indices 0 and 3 survive: the two dropped paragraphs leave a hole instead of
            // pulling "Body text" down to ordinal 1.
            assertThat(content.blocks()).extracting(DocumentBlock::ordinal).containsExactly(0, 3);
        }
    }

    @Nested
    class Headings {

        @Test
        void readsWordOutlineStylesAsLevels() throws Exception {
            Path file = writeDocx("headings.docx", doc -> {
                paragraph(doc, "Heading1", "Part");
                paragraph(doc, "Heading3", "Detail");
                paragraph(doc, null, "Body");
            });

            DocumentContent content = extractor.extract(file.toFile());

            assertThat(content.blocks()).extracting(DocumentBlock::headingLevel).containsExactly(1, 3, 0);
        }

        @Test
        void treatsUnknownAndDeeperStylesAsBodyText() throws Exception {
            Path file = writeDocx("styles.docx", doc -> {
                paragraph(doc, "Heading7", "Too deep for the reader's contents");
                paragraph(doc, "Quote", "Not a heading at all");
            });

            DocumentContent content = extractor.extract(file.toFile());

            assertThat(content.blocks()).extracting(DocumentBlock::headingLevel).containsOnly(0);
        }
    }

    @Test
    void recognisesBothWordContainerFormats() {
        assertThat(extractor.supports("report.doc")).isTrue();
        assertThat(extractor.supports("report.DOCX")).isTrue();
        assertThat(extractor.supports("report.epub")).isFalse();
    }
}
