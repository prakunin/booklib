package org.booklore.service.metadata.smart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.SmartEnrichmentProperties;
import org.booklore.model.dto.settings.SmartEnrichmentSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Runs the Antigravity CLI ({@code agy}) in its non-interactive print mode.
 * <p>
 * Unlike {@link org.booklore.service.system.TimeoutProcessRunner}, which probes tool versions and
 * only needs the first line within seconds, a prompt run reads the whole response and waits
 * minutes: the agent searches the web before it answers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgyCliClient implements AgentCliClient {

    private final SmartEnrichmentProperties properties;
    private final AppSettingService appSettingService;

    @Override
    public Optional<String> run(String prompt) {
        List<String> command = new ArrayList<>();
        command.add(properties.getBinaryPath());
        // Model and effort come from the settings row rather than YAML, so switching models is a
        // UI action instead of a restart.
        SmartEnrichmentSettings settings = appSettingService.getAppSettings().getSmartEnrichmentSettings();
        String model = settings == null ? null : settings.getModel();
        String effort = settings == null ? null : settings.getEffort();
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        if (effort != null && !effort.isBlank()) {
            command.add("--effort");
            command.add(effort);
        }
        command.add("-p");
        command.add(prompt);

        // The whole request and the whole reply are logged at INFO on purpose: this is a manual,
        // low-frequency operator action, and when a run goes wrong (a killed timeout, a model that
        // returns prose instead of JSON) the only way to see why is to read exactly what was sent
        // and what came back. Truncate nothing.
        log.info("Agent resolution request (model={}, effort={}, timeout={}s):\n{}",
                model == null || model.isBlank() ? "<cli default>" : model,
                effort == null || effort.isBlank() ? "<cli default>" : effort,
                properties.getTimeout().toSeconds(), prompt);

        long startNanos = System.nanoTime();
        Optional<String> output = execute(command, properties.getTimeout());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (output.isPresent()) {
            log.info("Agent resolution reply in {} ms ({} chars):\n{}",
                    elapsedMs, output.get().length(), output.get());
        } else {
            log.warn("Agent resolution produced no usable output after {} ms (see warnings above for cause)", elapsedMs);
        }
        return output;
    }

    @Override
    public Optional<String> runCommand(List<String> args, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(properties.getBinaryPath());
        command.addAll(args);
        return execute(command, timeout);
    }

    private Optional<String> execute(List<String> command, Duration timeout) {
        Process process = null;
        try {
            // Merged streams: the agent writes progress chatter to stderr, and keeping it lets a
            // failed run be diagnosed from the log. AgentResponseJsonExtractor picks the JSON out
            // of whatever surrounds it, so the noise costs nothing on the success path.
            process = new ProcessBuilder(command)
                    .directory(new File(properties.getWorkingDirectory()))
                    .redirectErrorStream(true)
                    .start();

            long timeoutSeconds = timeout.toSeconds();
            AtomicReference<String> output = new AtomicReference<>();
            Process started = process;
            Thread reader = Thread.ofVirtual().unstarted(() -> output.set(readAll(started)));
            reader.start();

            boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("Agent CLI did not finish within {}s, killing it", timeoutSeconds);
                process.destroyForcibly();
                // Destroying the process closes the pipe, which unblocks the reader on its own.
                reader.join();
                return Optional.empty();
            }
            reader.join();

            String text = output.get();
            if (process.exitValue() != 0) {
                log.warn("Agent CLI exited with {}: {}", process.exitValue(), abbreviate(text));
                return Optional.empty();
            }
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            log.warn("Could not run agent CLI '{}': {}", properties.getBinaryPath(), e.getMessage());
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String readAll(Process process) {
        try (BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return bufferedReader.lines().collect(Collectors.joining("\n"));
        } catch (IOException _) {
            // Stream closed mid-read, e.g. the process was killed. Whatever was buffered is lost.
            return null;
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "<no output>";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }
}
