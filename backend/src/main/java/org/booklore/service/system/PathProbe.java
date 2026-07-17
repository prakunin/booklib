package org.booklore.service.system;

import java.nio.file.FileStore;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Injection seam over java.nio.file.Files. Exists so the failure modes the System tab must survive —
 * a missing directory, an unreadable one, a network mount whose FileStore cannot be read, a network
 * mount whose server is gone entirely so the underlying syscall never returns — are reachable from
 * unit tests.
 */
public interface PathProbe {

    /**
     * @return whether {@code path} is a directory, or empty when that could not be determined
     *     within this probe's time budget (e.g. a hung network mount) — distinct from a definite
     *     "no".
     */
    Optional<Boolean> isDirectory(Path path);

    /**
     * @return whether {@code path} is readable, or empty when that could not be determined within
     *     this probe's time budget — distinct from a definite "no".
     */
    Optional<Boolean> isReadable(Path path);

    /**
     * @return the store backing this path, or empty when it cannot be resolved.
     */
    Optional<FileStore> fileStore(Path path);
}
