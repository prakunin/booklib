package org.booklore.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class TimeoutProcessRunner implements ProcessRunner {

    private static final int TIMEOUT_SECONDS = 5;

    @Override
    public Optional<String> firstLine(Path binary, String... args) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(List.of(args));

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            // A wedged binary must not hang the request.
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Timed out reading version from {}", binary);
                return Optional.empty();
            }
            if (process.exitValue() != 0 || line == null || line.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(line.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Could not read version from {}: {}", binary, e.getMessage());
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
