package org.booklore.service.metadata.extractor;

import org.apache.poi.hpsf.PropertySetFactory;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocMetadataExtractorTest {

    private final DocMetadataExtractor extractor = new DocMetadataExtractor();

    @TempDir
    Path tempDir;

    @Test
    void readsTitleAndCreatorFromDocxCoreProperties() throws Exception {
        Path file = tempDir.resolve("book.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("body");
            doc.getProperties().getCoreProperties().setTitle("Die Schuldfrage");
            doc.getProperties().getCoreProperties().setCreator("Karl Jaspers");
            try (OutputStream out = Files.newOutputStream(file)) {
                doc.write(out);
            }
        }

        BookMetadata metadata = extractor.extractMetadata(file.toFile());

        assertThat(metadata.getTitle()).isEqualTo("Die Schuldfrage");
        assertThat(metadata.getAuthors()).containsExactly("Karl Jaspers");
    }

    @Test
    void readsTitleAndAuthorFromDocSummaryInformation() throws Exception {
        Path file = tempDir.resolve("book.doc");
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            SummaryInformation summary = PropertySetFactory.newSummaryInformation();
            summary.setTitle("23 iyunya");
            summary.setAuthor("Mark Solonin");
            summary.write(fs.getRoot(), SummaryInformation.DEFAULT_STREAM_NAME);
            try (OutputStream out = Files.newOutputStream(file)) {
                fs.writeFilesystem(out);
            }
        }

        BookMetadata metadata = extractor.extractMetadata(file.toFile());

        assertThat(metadata.getTitle()).isEqualTo("23 iyunya");
        assertThat(metadata.getAuthors()).containsExactly("Mark Solonin");
    }

    @Test
    void leavesFieldsBlankWhenTheDocumentHasNoProperties() throws Exception {
        // OLE2 with an empty SummaryInformation — a genuinely property-less document. (A blank .docx
        // cannot be used here: POI stamps a default creator of "Apache POI" onto new packages.)
        Path file = tempDir.resolve("empty.doc");
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            SummaryInformation summary = PropertySetFactory.newSummaryInformation();
            summary.write(fs.getRoot(), SummaryInformation.DEFAULT_STREAM_NAME);
            try (OutputStream out = Files.newOutputStream(file)) {
                fs.writeFilesystem(out);
            }
        }

        BookMetadata metadata = extractor.extractMetadata(file.toFile());

        assertThat(metadata.getTitle()).isNull();
        assertThat(metadata.getAuthors()).isEmpty();
    }
}
