package org.booklore.service.djvu;

import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DjvuToolRunnerTest {

    private final FileService fileService = mock(FileService.class);
    private final DjvuCommandRunner commandRunner = mock(DjvuCommandRunner.class);
    private final DjvuToolRunner runner = new DjvuToolRunner(fileService, commandRunner);

    private final Path file = Path.of("/books/scan.djvu");

    @BeforeEach
    void toolsArePresent() {
        when(fileService.findSystemFile("djvused")).thenReturn(Path.of("/usr/bin/djvused"));
        when(fileService.findSystemFile("ddjvu")).thenReturn(Path.of("/usr/bin/ddjvu"));
    }

    @Nested
    class Probe {

        @Test
        void readsPageCountSizesAndMetadata() {
            when(commandRunner.text(any(), argThat(containsScript("n")), any())).thenReturn("2\n");
            when(commandRunner.text(any(), argThat(containsScript("select 1; size;")), any()))
                    .thenReturn("width=120 height=160\nwidth=100 height=140\n");
            when(commandRunner.text(any(), argThat(containsScript("print-meta")), any()))
                    .thenReturn("Title\t\"Radio Magazine\"\nAuthor\t\"A. Popov\"\n");

            DjvuDocumentInfo info = runner.probe(file);

            assertThat(info.pageCount()).isEqualTo(2);
            assertThat(info.pageSizes()).containsExactly(
                    new DjvuDocumentInfo.PageSize(120, 160),
                    new DjvuDocumentInfo.PageSize(100, 140));
            assertThat(info.metadata())
                    .containsEntry("Title", "Radio Magazine")
                    .containsEntry("Author", "A. Popov");
        }

        @Test
        void aDocumentWithoutMetadataProbesCleanly() {
            when(commandRunner.text(any(), argThat(containsScript("n")), any())).thenReturn("1\n");
            when(commandRunner.text(any(), argThat(containsScript("select 1; size;")), any()))
                    .thenReturn("width=10 height=20\n");
            when(commandRunner.text(any(), argThat(containsScript("print-meta")), any())).thenReturn("");

            assertThat(runner.probe(file).metadata()).isEmpty();
        }

        @Test
        void anUnparseableMetadataLineIsDroppedRatherThanFailingTheProbe() {
            when(commandRunner.text(any(), argThat(containsScript("n")), any())).thenReturn("1\n");
            when(commandRunner.text(any(), argThat(containsScript("select 1; size;")), any()))
                    .thenReturn("width=10 height=20\n");
            when(commandRunner.text(any(), argThat(containsScript("print-meta")), any()))
                    .thenReturn("this is not a metadata record\nTitle\t\"Kept\"\n");

            assertThat(runner.probe(file).metadata()).containsExactly(java.util.Map.entry("Title", "Kept"));
        }

        @Test
        void aNonNumericPageCountFailsLoudly() {
            when(commandRunner.text(any(), anyList(), any())).thenReturn("djvused: cannot open file\n");

            assertThatThrownBy(() -> runner.probe(file))
                    .isInstanceOf(DjvuToolException.class)
                    .hasMessageContaining("page count");
        }

        @Test
        void missingDjvusedFailsLoudly() {
            when(fileService.findSystemFile("djvused")).thenReturn(null);

            assertThatThrownBy(() -> runner.probe(file))
                    .isInstanceOf(DjvuToolException.class)
                    .hasMessageContaining("djvused");
        }
    }

    @Nested
    class RenderPage {

        @Test
        void asksDdjvuForTheRequestedPageOnly() {
            stubPpm(2, 1);

            runner.renderPageAsJpeg(file, 7, 0, OutputStream.nullOutputStream());

            verify(commandRunner).binary(eq(Path.of("/usr/bin/ddjvu")),
                    argThat(args -> args.contains("-page=7") && args.contains("-format=ppm")),
                    any(), any());
        }

        @Test
        void rendersAtNaturalSizeWhenNoCapIsGiven() {
            stubPpm(2, 1);

            runner.renderPageAsJpeg(file, 1, 0, OutputStream.nullOutputStream());

            verify(commandRunner).binary(any(),
                    argThat(args -> args.stream().noneMatch(arg -> arg.startsWith("-size="))),
                    any(), any());
        }

        @Test
        void passesTheCapAsAFittingBox() {
            stubPpm(2, 1);

            runner.renderPageAsJpeg(file, 1, 2000, OutputStream.nullOutputStream());

            verify(commandRunner).binary(any(), argThat(args -> args.contains("-size=2000x2000")), any(), any());
        }

        @Test
        void writesDecodableJpegBytes() throws Exception {
            stubPpm(2, 1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            runner.renderPageAsJpeg(file, 1, 0, out);

            assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(out.toByteArray())))
                    .isNotNull();
        }

        @Test
        void aTruncatedRenderFailsRatherThanProducingAPartialPage() {
            byte[] truncated = "P6\n4 4\n255\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            stubRaw(truncated);
            OutputStream output = OutputStream.nullOutputStream();

            assertThatThrownBy(() -> runner.renderPageAsJpeg(file, 1, 0, output))
                    .isInstanceOf(DjvuToolException.class)
                    .hasMessageContaining("Truncated");
        }

        @Test
        void missingDdjvuFailsBeforeAnythingIsRun() {
            when(fileService.findSystemFile("ddjvu")).thenReturn(null);
            OutputStream output = OutputStream.nullOutputStream();

            assertThatThrownBy(() -> runner.renderPageAsJpeg(file, 1, 0, output))
                    .isInstanceOf(DjvuToolException.class)
                    .hasMessageContaining("ddjvu");
            verify(commandRunner, never()).binary(any(), anyList(), any(), any());
        }
    }

    @Nested
    class Version {

        @Test
        void readsTheBannerDjvulibrePrintsOnStderr() {
            when(commandRunner.firstStderrLine(any(), anyList(), any()))
                    .thenReturn(java.util.Optional.of("DJVUSED --- DjVuLibre-3.5.29"));

            assertThat(runner.version()).contains("DjVuLibre-3.5.29");
        }

        @Test
        void isProbedOnceAndThenRemembered() {
            when(commandRunner.firstStderrLine(any(), anyList(), any()))
                    .thenReturn(java.util.Optional.of("DJVUSED --- DjVuLibre-3.5.29"));

            runner.version();
            runner.version();

            verify(commandRunner, times(1)).firstStderrLine(any(), anyList(), any());
        }

        @Test
        void aFailedProbeIsNotRememberedSoALoadedHostDoesNotLookToolless() {
            when(commandRunner.firstStderrLine(any(), anyList(), any()))
                    .thenReturn(java.util.Optional.empty())
                    .thenReturn(java.util.Optional.of("DJVUSED --- DjVuLibre-3.5.29"));

            assertThat(runner.version()).isEmpty();
            assertThat(runner.version()).contains("DjVuLibre-3.5.29");
        }

        @Test
        void reportsNothingWhenDjvusedIsAbsent() {
            when(fileService.findSystemFile("djvused")).thenReturn(null);

            assertThat(runner.version()).isEmpty();
        }

        @Test
        void unrecognisedOutputIsNotPassedOff() {
            when(commandRunner.firstStderrLine(any(), anyList(), any()))
                    .thenReturn(java.util.Optional.of("SECRET_KEY=hunter2"));

            assertThat(runner.version()).isEmpty();
        }
    }

    @Test
    void availabilityFollowsTheDdjvuBinary() {
        assertThat(runner.isAvailable()).isTrue();

        when(fileService.findSystemFile("ddjvu")).thenReturn(null);
        assertThat(runner.isAvailable()).isFalse();
    }

    private void stubPpm(int width, int height) {
        byte[] header = ("P6\n" + width + " " + height + "\n255\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] ppm = new byte[header.length + width * height * 3];
        System.arraycopy(header, 0, ppm, 0, header.length);
        stubRaw(ppm);
    }

    private void stubRaw(byte[] payload) {
        org.mockito.Mockito.doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(2);
            out.write(payload);
            return null;
        }).when(commandRunner).binary(any(), anyList(), any(), any(Duration.class));
    }

    private static org.mockito.ArgumentMatcher<List<String>> containsScript(String fragment) {
        return args -> args != null && args.stream().anyMatch(arg -> arg.contains(fragment));
    }
}
