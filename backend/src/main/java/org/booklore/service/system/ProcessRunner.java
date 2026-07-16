package org.booklore.service.system;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Injection seam over ProcessBuilder, so tool-version lookup can be unit tested without executing
 * anything.
 */
public interface ProcessRunner {

    /**
     * @return the first line the command writes, or empty if it cannot be run, prints nothing
     * before it is killed, or exits non-zero before the timeout elapses. A process that already
     * printed a line but is still slow to exit is <strong>not</strong> treated as a failure: for a
     * "-version" probe, a version string the child already wrote is useful information even if it
     * then lingers or hangs on shutdown, and discarding it on that basis would only make
     * diagnostics worse. The timeout bounds how long the caller waits, not whether an
     * already-captured line is honored.
     */
    Optional<String> firstLine(Path binary, String... args);
}
