package org.booklore.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
            // The read and the exit wait share a single deadline: a child that never prints a
            // line, or one that prints promptly but exits slowly, must not hang the request any
            // longer than timeoutSeconds in total.
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            String line = readFirstLine(process, deadlineNanos);
            long remainingNanos = deadlineNanos - System.nanoTime();
            // Unlike Thread.join(long) below, Process.waitFor(long, TimeUnit) documents that a
            // non-positive timeout means "poll once and return immediately" — the opposite trap to
            // the one that bit readFirstLine's join. Math.max(0, ...) is correct here and must stay;
            // do not "unify" this with the Duration-based join fix below.
            boolean exited = process.waitFor(Math.max(0, remainingNanos), TimeUnit.NANOSECONDS);
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
     * Reads the first line of the process's stdout on a dedicated virtual thread, bounded by the
     * given {@code deadlineNanos} (a {@link System#nanoTime()} instant, shared with the caller's
     * subsequent {@code waitFor}, so the two stages never add up to more than
     * {@link #timeoutSeconds} combined). If the child never writes a line, the read stays blocked
     * past the deadline, but the caller destroys the process in its {@code finally} block once
     * that same deadline is exhausted, which unblocks the pipe and lets the thread exit on its
     * own.
     */
    private String readFirstLine(Process process, long deadlineNanos) throws InterruptedException {
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
        // Thread.join(long millis) treats 0 as "wait forever", not "don't wait" — so clamping an
        // already-exhausted deadline to 0 with Math.max(0, ...) previously reintroduced the exact
        // hang this class exists to prevent. Thread.join(Duration) does not have that trap: per its
        // javadoc, a duration of zero or less means "do not wait", which is exactly "give up now"
        // semantics an exhausted deadline needs.
        reader.join(Duration.ofNanos(deadlineNanos - System.nanoTime()));
        return result.get();
    }
}
