package org.booklore.service.system;

import java.nio.file.FileStore;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Injection seam over java.nio.file.Files. Exists so the failure modes the System tab must survive —
 * a missing directory, an unreadable one, a network mount whose FileStore cannot be read — are
 * reachable from unit tests.
 */
public interface PathProbe {

    boolean isDirectory(Path path);

    boolean isReadable(Path path);

    /**
     * @return the store backing this path, or empty when it cannot be resolved.
     */
    Optional<FileStore> fileStore(Path path);
}
