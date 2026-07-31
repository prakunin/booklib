package org.booklore.service.djvu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs djvulibre binaries.
 * <p>
 * stderr is drained on a separate thread rather than merged into stdout: {@code ddjvu} writes the
 * image to stdout, so merging would corrupt every rendered page, and leaving stderr unread would
 * deadlock the child as soon as it fills the pipe buffer.
 */
@Slf4j
@Component
public class ProcessDjvuCommandRunner implements DjvuCommandRunner {

    @Override
    public String text(Path binary, List<String> args, Duration timeout) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        binary(binary, args, out, timeout);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void binary(Path binary, List<String> args, OutputStream out, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(binary.toAbsolutePath().toString());
        command.addAll(args);

        Process process = start(command);
        // Written by the drain thread, read by this one. A StringBuffer rather than a StringBuilder
        // because a join that times out leaves the two threads racing over it.
        StringBuffer stderr = new StringBuffer();
        Thread stderrDrain = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));
        try {
            process.getInputStream().transferTo(out);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new DjvuToolException(binary.getFileName() + " timed out after " + timeout);
            }
            stderrDrain.join(1000);
            if (process.exitValue() != 0) {
                throw new DjvuToolException(binary.getFileName() + " exited with "
                        + process.exitValue() + ": " + stderr.toString().strip());
            }
        } catch (IOException e) {
            process.destroyForcibly();
            throw new DjvuToolException("Failed to read output of " + binary.getFileName(), e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new DjvuToolException("Interrupted while running " + binary.getFileName(), e);
        }
    }

    @Override
    public Optional<String> firstStderrLine(Path binary, List<String> args, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(binary.toAbsolutePath().toString());
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            log.debug("Could not start {} for a version probe: {}", binary.getFileName(), e.getMessage());
            return Optional.empty();
        }
        try {
            StringBuffer stdout = new StringBuffer();
            Thread stdoutDrain = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
            String line = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                    .lines().findFirst().orElse("").strip();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
            stdoutDrain.join(1000);
            return line.isEmpty() ? Optional.empty() : Optional.of(line);
        } catch (IOException e) {
            process.destroyForcibly();
            log.debug("Could not read the banner of {}: {}", binary.getFileName(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private Process start(List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new DjvuToolException("Failed to start " + command.getFirst(), e);
        }
    }

    private void drain(InputStream stream, StringBuffer sink) {
        try (stream) {
            sink.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Failed to read stderr of a djvulibre process: {}", e.getMessage());
        }
    }
}
