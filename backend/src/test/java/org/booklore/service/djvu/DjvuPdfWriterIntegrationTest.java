package org.booklore.service.djvu;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.booklore.config.AppProperties;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Proves the property the whole rendition exists for: the PDF is <em>searchable</em>.
 * <p>
 * This is not a detail that can be assumed. The obvious route to PDF - {@code djvups} into
 * ghostscript - drops the hidden text silently and produces a file that looks perfectly fine and
 * cannot be searched at all, which is exactly why the rendition is assembled here instead. A test
 * that only checked the file opened would have passed on that route too.
 */
@DisplayName("DjVu PDF rendition against real djvulibre binaries")
@EnabledIf("org.booklore.service.djvu.DjvuPdfWriterIntegrationTest#djvulibreIsInstalled")
class DjvuPdfWriterIntegrationTest {

    private static FileService fileService() {
        AppProperties appProperties = mock(AppProperties.class);
        lenient().when(appProperties.getPathConfig()).thenReturn("/nonexistent-config-path");
        return new FileService(appProperties, mock(RestTemplate.class), mock(AppSettingService.class),
                mock(RestTemplate.class));
    }

    static boolean djvulibreIsInstalled() {
        FileService fileService = fileService();
        return fileService.findSystemFile("ddjvu") != null && fileService.findSystemFile("djvused") != null;
    }

    private final DjvuToolRunner toolRunner = new DjvuToolRunner(fileService(), new ProcessDjvuCommandRunner());
    private final DjvuPdfWriter writer = new DjvuPdfWriter(toolRunner);

    @TempDir
    Path tempDir;

    private static Path fixture(String name) {
        return Paths.get(Objects.requireNonNull(
                DjvuPdfWriterIntegrationTest.class.getResource("/djvu/" + name)).getPath());
    }

    @Test
    void thePdfCarriesOnePageEachAndTheDocumentsHiddenText() throws Exception {
        Path source = fixture("two-page-with-text.djvu");
        Path target = tempDir.resolve("rendition.pdf");

        writer.write(source, toolRunner.probe(source), target, null);

        assertThat(target).exists();
        try (PDDocument pdf = Loader.loadPDF(target.toFile())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            assertThat(new PDFTextStripper().getText(pdf)).contains("Hello").contains("World");
        }
    }

    @Test
    void aScanWithoutATextLayerStillRenders() throws Exception {
        Path source = fixture("two-page-with-metadata.djvu");
        Path target = tempDir.resolve("no-text.pdf");

        writer.write(source, toolRunner.probe(source), target, null);

        try (PDDocument pdf = Loader.loadPDF(target.toFile())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            assertThat(new PDFTextStripper().getText(pdf).strip()).isEmpty();
        }
    }

    @Test
    void progressIsReportedOncePerPage() {
        Path source = fixture("two-page-with-text.djvu");
        java.util.List<Integer> reported = new java.util.ArrayList<>();

        writer.write(source, toolRunner.probe(source), tempDir.resolve("progress.pdf"), reported::add);

        assertThat(reported).containsExactly(1, 2);
    }

    @Test
    void theRenditionIsWrittenIntoADirectoryThatDoesNotExistYet() {
        Path source = fixture("two-page-with-text.djvu");
        Path target = tempDir.resolve("nested").resolve("deeper").resolve("rendition.pdf");

        writer.write(source, toolRunner.probe(source), target, null);

        assertThat(Files.isRegularFile(target)).isTrue();
    }
}
