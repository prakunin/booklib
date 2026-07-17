package org.booklore.service.system;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link NioPathProbe} correctly delegates to {@link Files} and wraps results in
 * {@link Optional}, for the well-behaved case. The bounding behaviour it relies on for a genuinely
 * hung syscall is {@link TimeoutGuard}'s responsibility and is proven exhaustively (and with a real
 * wall-clock assertion) in {@link TimeoutGuardTest}; that scenario cannot be reproduced here with a
 * real filesystem call without an actual hung mount.
 */
class NioPathProbeTest {

    private final NioPathProbe probe = new NioPathProbe(new TimeoutGuard());

    @Nested
    class IsDirectory {

        @Test
        void reportsTrueForARealDirectory(@TempDir Path tempDir) {
            assertThat(probe.isDirectory(tempDir)).contains(true);
        }

        @Test
        void reportsFalseForAMissingPath(@TempDir Path tempDir) {
            assertThat(probe.isDirectory(tempDir.resolve("does-not-exist"))).contains(false);
        }

        @Test
        void reportsFalseForARegularFile(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("a-file.txt"));

            assertThat(probe.isDirectory(file)).contains(false);
        }
    }

    @Nested
    class IsReadable {

        @Test
        void reportsTrueForAReadableDirectory(@TempDir Path tempDir) {
            assertThat(probe.isReadable(tempDir)).contains(true);
        }
    }

    @Nested
    class FileStore {

        @Test
        void resolvesTheStoreBackingARealDirectory(@TempDir Path tempDir) {
            assertThat(probe.fileStore(tempDir)).isPresent();
        }

        @Test
        void isEmptyForAPathThatCannotBeResolved() {
            // A path with no backing directory makes Files.getFileStore throw
            // NoSuchFileException — proving the try/catch inside the guarded callable turns that
            // into an empty Optional rather than propagating.
            assertThat(probe.fileStore(Path.of("/definitely/not/a/real/mount-xyz-123"))).isEmpty();
        }
    }
}
