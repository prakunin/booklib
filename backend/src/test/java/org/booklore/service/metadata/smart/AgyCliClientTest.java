package org.booklore.service.metadata.smart;

import org.booklore.config.SmartEnrichmentProperties;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.SmartEnrichmentSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgyCliClientTest {

    @TempDir
    Path tempDir;

    private final SmartEnrichmentProperties properties = new SmartEnrichmentProperties();
    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private AgyCliClient client;

    @BeforeEach
    void setUp() {
        properties.setWorkingDirectory(tempDir.toString());
        properties.setTimeout(Duration.ofSeconds(2));
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder()
                .smartEnrichmentSettings(SmartEnrichmentSettings.builder().build())
                .build());
        client = new AgyCliClient(properties, appSettingService);
    }

    @Test
    void runPassesConfiguredModelEffortAndPrompt() throws IOException {
        properties.setBinaryPath(script("arguments", "printf '%s\\n' \"$@\"").toString());
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder()
                .smartEnrichmentSettings(SmartEnrichmentSettings.builder()
                        .model("model-v1")
                        .effort("high")
                        .build())
                .build());

        Optional<String> output = client.run("identify this book");

        assertThat(output).contains("--model\nmodel-v1\n--effort\nhigh\n-p\nidentify this book");
    }

    @Test
    void runUsesCliDefaultsWhenSettingsAreAbsent() throws IOException {
        properties.setBinaryPath(script("defaults", "printf '%s\\n' \"$@\"").toString());
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder().build());

        assertThat(client.run("prompt")).contains("-p\nprompt");
    }

    @Test
    void runCommandReturnsOutputAndRejectsBlankOrFailedCommands() throws IOException {
        properties.setBinaryPath(script("success", "printf 'version 1.2\\n'").toString());
        assertThat(client.runCommand(List.of("--version"), Duration.ofSeconds(1)))
                .contains("version 1.2");

        properties.setBinaryPath(script("blank", ":").toString());
        assertThat(client.runCommand(List.of(), Duration.ofSeconds(1))).isEmpty();

        properties.setBinaryPath(script("failure", "printf 'bad' >&2; exit 2").toString());
        assertThat(client.runCommand(List.of(), Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheBinaryCannotStartOrTimesOut() throws IOException {
        properties.setBinaryPath(tempDir.resolve("missing").toString());
        assertThat(client.runCommand(List.of(), Duration.ofSeconds(1))).isEmpty();

        properties.setBinaryPath(script("slow", "sleep 2; printf 'late'").toString());
        assertThat(client.runCommand(List.of(), Duration.ZERO)).isEmpty();
    }

    private Path script(String name, String body) throws IOException {
        Path path = tempDir.resolve(name + ".sh");
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return path;
    }
}
