package org.booklore.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
        } catch (IOException e) {
            log.debug("Could not resolve file store for {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }
}
