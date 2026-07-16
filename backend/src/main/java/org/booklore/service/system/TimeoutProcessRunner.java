package org.booklore.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class TimeoutProcessRunner implements ProcessRunner {

    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    private final int timeoutSeconds;

    public TimeoutProcessRunner() {
        this(DEFAULT_TIMEOUT_SECONDS);
    }

    // Package-private: lets tests use a short timeout instead of sleeping through the real one.
    TimeoutProcessRunner(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Optional<String> firstLine(Path binary, String... args) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(List.of(args));

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Both the read and the exit wait get their own bound: a child that never prints a
            // line must not hang the request any longer than one that never exits.
            String line = readFirstLine(process);
            boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("Timed out waiting for {} to exit", binary);
            }
            if (line == null || line.isBlank()) {
                return Optional.empty();
            }
            // A line was actually read: prefer it over discarding it just because the process
            // lingered past the timeout before exiting.
            if (exited && process.exitValue() != 0) {
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

    /**
     * Reads the first line of the process's stdout on a dedicated virtual thread, bounded by
     * {@link #timeoutSeconds}. If the child never writes a line, the read stays blocked past the
     * deadline, but the caller destroys the process shortly after regardless, which unblocks the
     * pipe and lets the thread exit on its own.
     */
    private String readFirstLine(Process process) throws InterruptedException {
        AtomicReference<String> result = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().unstarted(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                result.set(bufferedReader.readLine());
            } catch (IOException e) {
                // Stream closed while the read was blocked, e.g. the process was killed. No line.
            }
        });
        reader.start();
        reader.join(TimeUnit.SECONDS.toMillis(timeoutSeconds));
        return result.get();
    }
}
