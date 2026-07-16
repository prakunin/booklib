package org.booklore.service.system;

import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolVersionServiceTest {

    @Mock
    private FileService fileService;
    @Mock
    private ProcessRunner processRunner;

    private ToolVersionService service;

    @BeforeEach
    void setUp() {
        service = new ToolVersionService(fileService, processRunner);
    }

    @Nested
    class WhenBinariesArePresent {

        @Test
        void reportsBothVersions() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(Path.of("/usr/local/bin/ffprobe"));
            when(fileService.findSystemFile("kepubify")).thenReturn(Path.of("/usr/local/bin/kepubify"));
            when(processRunner.firstLine(Path.of("/usr/local/bin/ffprobe"), "-version"))
                    .thenReturn(Optional.of("ffprobe version 8.1.2"));
            when(processRunner.firstLine(Path.of("/usr/local/bin/kepubify"), "--version"))
                    .thenReturn(Optional.of("kepubify v4.0.4"));

            var tools = service.toolsInfo();

            assertThat(tools.getFfprobeVersion()).isEqualTo("ffprobe version 8.1.2");
            assertThat(tools.getKepubifyVersion()).isEqualTo("kepubify v4.0.4");
        }

        @Test
        void executesEachBinaryOnlyOnceAcrossCalls() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(Path.of("/usr/local/bin/ffprobe"));
            when(fileService.findSystemFile("kepubify")).thenReturn(Path.of("/usr/local/bin/kepubify"));
            when(processRunner.firstLine(any(), any())).thenReturn(Optional.of("tool version 1.0.0"));

            service.toolsInfo();
            service.toolsInfo();
            service.toolsInfo();

            verify(processRunner, times(1)).firstLine(Path.of("/usr/local/bin/ffprobe"), "-version");
            verify(processRunner, times(1)).firstLine(Path.of("/usr/local/bin/kepubify"), "--version");
        }
    }

    @Nested
    class WhenTheOutputDoesNotLookLikeAVersion {

        @Test
        void reportsNullInsteadOfAnEnvironmentAssignment() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(Path.of("/usr/local/bin/ffprobe"));
            when(fileService.findSystemFile("kepubify")).thenReturn(Path.of("/usr/local/bin/kepubify"));
            when(processRunner.firstLine(Path.of("/usr/local/bin/ffprobe"), "-version"))
                    .thenReturn(Optional.of("TOKEN=secret123"));
            when(processRunner.firstLine(Path.of("/usr/local/bin/kepubify"), "--version"))
                    .thenReturn(Optional.of("kepubify v4.0.4"));

            var tools = service.toolsInfo();

            assertThat(tools.getFfprobeVersion()).isNull();
            assertThat(tools.getKepubifyVersion()).isEqualTo("kepubify v4.0.4");
        }

        @Test
        void reportsNullInsteadOfAConfigLikeAssignment() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(Path.of("/usr/local/bin/ffprobe"));
            when(fileService.findSystemFile("kepubify")).thenReturn(null);
            when(processRunner.firstLine(Path.of("/usr/local/bin/ffprobe"), "-version"))
                    .thenReturn(Optional.of("DATABASE_URL=jdbc:mariadb://db:3306/booklore"));

            assertThat(service.toolsInfo().getFfprobeVersion()).isNull();
        }

        @Test
        void reportsNullInsteadOfAnOverlyLongLine() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(Path.of("/usr/local/bin/ffprobe"));
            when(fileService.findSystemFile("kepubify")).thenReturn(null);
            when(processRunner.firstLine(Path.of("/usr/local/bin/ffprobe"), "-version"))
                    .thenReturn(Optional.of("ffprobe version 1.0.0 " + "x".repeat(200)));

            assertThat(service.toolsInfo().getFfprobeVersion()).isNull();
        }

        @Test
        void acceptsAPlausibleVersionLineUnchanged() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(Path.of("/usr/local/bin/ffprobe"));
            when(fileService.findSystemFile("kepubify")).thenReturn(null);
            when(processRunner.firstLine(Path.of("/usr/local/bin/ffprobe"), "-version"))
                    .thenReturn(Optional.of("ffprobe version 8.1.2"));

            assertThat(service.toolsInfo().getFfprobeVersion()).isEqualTo("ffprobe version 8.1.2");
        }
    }

    @Nested
    class WhenAProbeFailsTransiently {

        @Test
        void doesNotCacheAnEmptyResultAndRetriesOnTheNextCall() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(Path.of("/usr/local/bin/ffprobe"));
            when(fileService.findSystemFile("kepubify")).thenReturn(null);
            when(processRunner.firstLine(Path.of("/usr/local/bin/ffprobe"), "-version"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of("ffprobe version 8.1.2"));

            assertThat(service.toolsInfo().getFfprobeVersion()).isNull();
            assertThat(service.toolsInfo().getFfprobeVersion()).isEqualTo("ffprobe version 8.1.2");

            verify(processRunner, times(2)).firstLine(Path.of("/usr/local/bin/ffprobe"), "-version");
        }

        @Test
        void stillCachesASuccessAfterAnEarlierFailureRatherThanProbingForever() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(Path.of("/usr/local/bin/ffprobe"));
            when(fileService.findSystemFile("kepubify")).thenReturn(null);
            when(processRunner.firstLine(Path.of("/usr/local/bin/ffprobe"), "-version"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of("ffprobe version 8.1.2"));

            service.toolsInfo();
            service.toolsInfo();
            service.toolsInfo();

            verify(processRunner, times(2)).firstLine(Path.of("/usr/local/bin/ffprobe"), "-version");
        }
    }

    @Nested
    class WhenSomethingIsMissing {

        @Test
        void reportsNullForAnAbsentBinaryWithoutFailing() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(null);
            when(fileService.findSystemFile("kepubify")).thenReturn(Path.of("/usr/local/bin/kepubify"));
            when(processRunner.firstLine(Path.of("/usr/local/bin/kepubify"), "--version"))
                    .thenReturn(Optional.of("kepubify v4.0.4"));

            var tools = service.toolsInfo();

            assertThat(tools.getFfprobeVersion()).isNull();
            assertThat(tools.getKepubifyVersion()).isEqualTo("kepubify v4.0.4");
        }

        @Test
        void reportsNullWhenTheBinaryFailsToRun() {
            when(fileService.findSystemFile("ffprobe")).thenReturn(Path.of("/usr/local/bin/ffprobe"));
            when(fileService.findSystemFile("kepubify")).thenReturn(null);
            when(processRunner.firstLine(Path.of("/usr/local/bin/ffprobe"), "-version"))
                    .thenReturn(Optional.empty());

            var tools = service.toolsInfo();

            assertThat(tools.getFfprobeVersion()).isNull();
            assertThat(tools.getKepubifyVersion()).isNull();
        }
    }
}
