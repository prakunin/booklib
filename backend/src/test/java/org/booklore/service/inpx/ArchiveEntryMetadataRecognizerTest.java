package org.booklore.service.inpx;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.metadata.extractor.DocMetadataExtractor;
import org.booklore.service.metadata.extractor.FileMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArchiveEntryMetadataRecognizerTest {

    private final MetadataExtractorFactory extractorFactory = mock(MetadataExtractorFactory.class);
    private final DocMetadataExtractor docMetadataExtractor = mock(DocMetadataExtractor.class);
    private final ArchiveEntryMetadataRecognizer recognizer = new ArchiveEntryMetadataRecognizer(
            extractorFactory, docMetadataExtractor, new InpxFilenameMetadataParser());

    private final File anyFile = new File("ignored");

    @Test
    void resolvesReadableTypeByExtensionAndOtherForTheRest() {
        assertThat(recognizer.resolveBookType("x.pdf")).isEqualTo(BookFileType.PDF);
        assertThat(recognizer.resolveBookType("x.fb2")).isEqualTo(BookFileType.FB2);
        // Word documents read through a synthesised rendition, so an archive entry resolves to the
        // same readable type a library folder would give it.
        assertThat(recognizer.resolveBookType("x.doc")).isEqualTo(BookFileType.DOC);
        assertThat(recognizer.resolveBookType("x.docx")).isEqualTo(BookFileType.DOC);
        // Formats that still have no reader stay download-only.
        assertThat(recognizer.resolveBookType("x.djvu")).isEqualTo(BookFileType.OTHER);
    }

    @Test
    void reportsWhichExtensionsHaveAnExtractor() {
        assertThat(recognizer.hasExtractor("x.pdf")).isTrue();
        assertThat(recognizer.hasExtractor("x.doc")).isTrue();
        assertThat(recognizer.hasExtractor("x.docx")).isTrue();
        assertThat(recognizer.hasExtractor("x.djvu")).isFalse();
    }

    @Test
    void fallsBackToFilenameWhenNoExtractorExists() {
        BookMetadata metadata = recognizer.recognize("Megan_Lindholm_Silver_Lady.djvu", anyFile);

        assertThat(metadata.getTitle()).isEqualTo("Silver Lady");
        assertThat(metadata.getAuthors()).containsExactly("Megan Lindholm");
    }

    @Test
    void prefersExtractorValuesOverTheFilenameBaseline() {
        FileMetadataExtractor pdf = mock(FileMetadataExtractor.class);
        when(pdf.extractMetadata(any())).thenReturn(BookMetadata.builder()
                .title("Real Title").authors(List.of("Real Author")).build());
        when(extractorFactory.getExtractor(BookFileType.PDF)).thenReturn(pdf);

        BookMetadata metadata = recognizer.recognize("Some_Person_Wrong_Title.pdf", anyFile);

        assertThat(metadata.getTitle()).isEqualTo("Real Title");
        assertThat(metadata.getAuthors()).containsExactly("Real Author");
    }

    @Test
    void fillsBlankExtractorFieldsFromTheFilenameBaseline() {
        FileMetadataExtractor pdf = mock(FileMetadataExtractor.class);
        when(pdf.extractMetadata(any())).thenReturn(BookMetadata.builder().build()); // no title, no authors
        when(extractorFactory.getExtractor(BookFileType.PDF)).thenReturn(pdf);

        BookMetadata metadata = recognizer.recognize("Mark_Semyonovich_Solonin_Den_M.pdf", anyFile);

        assertThat(metadata.getTitle()).isEqualTo("Den M");
        assertThat(metadata.getAuthors()).containsExactly("Mark Semyonovich Solonin");
    }

    @Test
    void usesDocExtractorForWordDocuments() {
        when(docMetadataExtractor.extractMetadata(any())).thenReturn(BookMetadata.builder()
                .title("From Doc").authors(List.of("Doc Author")).build());

        BookMetadata metadata = recognizer.recognize("Ignored_Name.docx", anyFile);

        assertThat(metadata.getTitle()).isEqualTo("From Doc");
        assertThat(metadata.getAuthors()).containsExactly("Doc Author");
    }

    @Test
    void withoutAFileUsesTheFilenameBaselineOnly() {
        BookMetadata metadata = recognizer.recognize("Mark_Semyonovich_Solonin_Den_M.pdf", null);

        assertThat(metadata.getTitle()).isEqualTo("Den M");
        assertThat(metadata.getAuthors()).containsExactly("Mark Semyonovich Solonin");
    }
}
