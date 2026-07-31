package org.booklore.service.djvu;

import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Injection seam over {@link ProcessBuilder}, so everything above it can be unit tested without
 * executing a binary. Mirrors {@code ProcessRunner}, which does the same for tool-version probes.
 */
public interface DjvuCommandRunner {

    /**
     * Runs the command and returns everything it wrote to stdout.
     *
     * @throws DjvuToolException if the process cannot be started, exits non-zero, or outlives
     *                           {@code timeout}
     */
    String text(Path binary, List<String> args, Duration timeout);

    /**
     * Runs the command and copies its stdout into {@code out}, without buffering the whole stream.
     *
     * @throws DjvuToolException if the process cannot be started, exits non-zero, or outlives
     *                           {@code timeout}
     */
    void binary(Path binary, List<String> args, OutputStream out, Duration timeout);

    /**
     * First line the command writes to <em>stderr</em>, whatever its exit code, or empty if it
     * wrote nothing or could not be run.
     * <p>
     * This exists because every djvulibre tool prints its banner ("DJVUSED --- DjVuLibre-3.5.28")
     * to stderr and then exits non-zero. The shared {@code ProcessRunner} reads stdout and treats a
     * non-zero exit as failure, both correctly - so rather than widening that contract for one
     * oddly-behaved family of binaries, the oddity is handled here, next to the tools that have it.
     */
    Optional<String> firstStderrLine(Path binary, List<String> args, Duration timeout);
}
