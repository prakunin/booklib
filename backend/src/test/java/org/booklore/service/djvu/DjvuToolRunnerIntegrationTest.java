package org.booklore.service.djvu;

import org.booklore.config.AppProperties;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Exercises the real djvulibre binaries against a two-page fixture.
 * <p>
 * The unit tests pin how the runner builds commands and parses their output; only this one proves
 * that the commands are the ones djvulibre actually accepts. It is skipped where the binaries are
 * absent, which means a green run on such a host has not checked any of this.
 */
@DisplayName("DjVu tool runner against real djvulibre binaries")
@EnabledIf("org.booklore.service.djvu.DjvuToolRunnerIntegrationTest#djvulibreIsInstalled")
class DjvuToolRunnerIntegrationTest {

    private final DjvuToolRunner runner = new DjvuToolRunner(fileService(), new ProcessDjvuCommandRunner());

    /**
     * Only {@code findSystemFile} is exercised here, and it needs nothing from the collaborators
     * beyond a config path to search before {@code $PATH}.
     */
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

    private static Path fixture() {
        return Paths.get(Objects.requireNonNull(
                        DjvuToolRunnerIntegrationTest.class.getResource("/djvu/two-page-with-metadata.djvu"))
                .getPath());
    }

    @Test
    void probeReadsPageCountSizesAndMetadataFromARealDocument() {
        DjvuDocumentInfo info = runner.probe(fixture());

        assertThat(info.pageCount()).isEqualTo(2);
        assertThat(info.pageSizes()).containsExactly(
                new DjvuDocumentInfo.PageSize(120, 160),
                new DjvuDocumentInfo.PageSize(100, 140));
        assertThat(info.metadata())
                .containsEntry("Title", "Radio Magazine")
                .containsEntry("Author", "A. Popov")
                .containsEntry("Year", "1972");
    }

    @Test
    void rendersEachPageAtItsNaturalSize() throws Exception {
        assertThat(renderedSize(1, 0)).isEqualTo(new int[]{120, 160});
        assertThat(renderedSize(2, 0)).isEqualTo(new int[]{100, 140});
    }

    @Test
    void theCapFitsThePageInsideTheBoxAndKeepsItsAspectRatio() throws Exception {
        int[] size = renderedSize(1, 80);

        // 120x160 fitted into 80x80 stays 3:4, so the height is the binding edge.
        assertThat(size).containsExactly(60, 80);
    }

    private int[] renderedSize(int page, int maxEdge) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        runner.renderPageAsJpeg(fixture(), page, maxEdge, out);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(image).isNotNull();
        return new int[]{image.getWidth(), image.getHeight()};
    }
}
