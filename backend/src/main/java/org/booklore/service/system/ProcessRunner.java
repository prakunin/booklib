package org.booklore.service.system;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Injection seam over ProcessBuilder, so tool-version lookup can be unit tested without executing
 * anything.
 */
public interface ProcessRunner {

    /**
     * @return the first line the command writes, or empty if it cannot be run, exits non-zero,
     * times out, or prints nothing.
     */
    Optional<String> firstLine(Path binary, String... args);
}
