package org.booklore.service.kobo;

import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KepubConversionServiceTest {

    @Mock
    private FileService fileService;

    @TempDir
    private Path tempDir;

    private KepubConversionService service;

    @BeforeEach
    void setUp() {
        service = new KepubConversionService(fileService);
    }

    @Test
    void convertEpubToKepubDrainsMergedOutputAndReturnsCreatedFile() throws Exception {
        Path epubFile = Files.writeString(tempDir.resolve("book.epub"), "epub", StandardCharsets.UTF_8);
        Path kepubify = tempDir.resolve("kepubify-test.sh");
        Files.writeString(kepubify, """
                #!/bin/sh
                echo "stdout"
                echo "stderr" >&2
                touch "$2/book.kepub.epub"
                """, StandardCharsets.UTF_8);
        assertThat(kepubify.toFile().setExecutable(true)).isTrue();
        when(fileService.findSystemFile("kepubify")).thenReturn(kepubify);

        File output = service.convertEpubToKepub(epubFile.toFile(), tempDir.toFile(), false);

        assertThat(output.getName()).isEqualTo("book.kepub.epub");
        assertThat(output).exists();
    }
}
