package org.booklore.service.system;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
@Component
@AllArgsConstructor
public class NioPathProbe implements PathProbe {

    private final TimeoutGuard timeoutGuard;

    @Override
    public Optional<Boolean> isDirectory(Path path) {
        // Files.isDirectory declares no checked exception and, on a hung network mount, can block
        // the underlying syscall forever without ever throwing — a plain catch block for Exception
        // cannot see that. timeoutGuard.run bounds the wait; see its javadoc for why the call is
        // simply abandoned, not cancelled, when the budget runs out.
        return timeoutGuard.run("Files.isDirectory(" + path + ")", () -> Files.isDirectory(path));
    }

    @Override
    public Optional<Boolean> isReadable(Path path) {
        return timeoutGuard.run("Files.isReadable(" + path + ")", () -> Files.isReadable(path));
    }

    @Override
    public Optional<FileStore> fileStore(Path path) {
        return timeoutGuard.run("Files.getFileStore(" + path + ")", () -> {
            try {
                return Files.getFileStore(path);
            } catch (Exception e) {
                // Files.getFileStore declares only IOException, but broken or unusual filesystem
                // providers (e.g. a network mount) can surface unchecked failures too
                // (SecurityException, etc.). Catching narrowly let one bad path's unchecked failure
                // escape past the caller's own guard and take the whole filesystems block down with it.
                log.debug("Could not resolve file store for {}: {}", path, e.getMessage());
                return null;
            }
        });
    }
}
