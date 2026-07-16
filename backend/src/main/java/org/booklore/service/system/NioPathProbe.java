package org.booklore.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
@Component
public class NioPathProbe implements PathProbe {

    @Override
    public boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    @Override
    public boolean isReadable(Path path) {
        return Files.isReadable(path);
    }

    @Override
    public Optional<FileStore> fileStore(Path path) {
        try {
            return Optional.of(Files.getFileStore(path));
        } catch (Exception e) {
            // Files.getFileStore declares only IOException, but broken or unusual filesystem
            // providers (e.g. a network mount) can surface unchecked failures too (SecurityException,
            // etc.). Catching narrowly let one bad path's unchecked failure escape past the caller's
            // own guard and take the whole filesystems block down with it.
            log.debug("Could not resolve file store for {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }
}
