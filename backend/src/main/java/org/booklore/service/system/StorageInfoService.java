package org.booklore.service.system;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.dto.system.FilesystemInfo;
import org.booklore.model.dto.system.LibraryPathInfo;
import org.booklore.model.dto.system.StorageInfo;
import org.booklore.model.enums.PathStatus;
import org.booklore.repository.LibraryPathRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@AllArgsConstructor
public class StorageInfoService {

    private final PathProbe pathProbe;
    private final LibraryPathRepository libraryPathRepository;
    private final AppProperties appProperties;

    public StorageInfo storageInfo() {
        return StorageInfo.builder()
                .diskType(appProperties.getDiskType())
                .build();
    }

    public List<LibraryPathInfo> libraryPaths() {
        List<LibraryPathInfo> result = new ArrayList<>();
        for (String path : configuredLibraryPaths()) {
            result.add(LibraryPathInfo.builder()
                    .path(path)
                    .status(classify(Path.of(path)))
                    .build());
        }
        return result;
    }

    /**
     * Free space is reported per distinct file store, not per path: several library paths commonly
     * share one disk, and repeating the same figure for each would be noise. Paths that cannot be
     * resolved contribute nothing here — they surface in {@link #libraryPaths()} with their status.
     */
    public List<FilesystemInfo> filesystems() {
        Map<FileStore, List<String>> pathsByStore = new LinkedHashMap<>();
        for (String path : allKnownPaths()) {
            Optional<FileStore> store = pathProbe.fileStore(Path.of(path));
            store.ifPresent(fileStore -> pathsByStore.computeIfAbsent(fileStore, k -> new ArrayList<>()).add(path));
        }

        List<FilesystemInfo> result = new ArrayList<>();
        pathsByStore.forEach((store, paths) -> toFilesystemInfo(store, paths).ifPresent(result::add));
        return result;
    }

    private Optional<FilesystemInfo> toFilesystemInfo(FileStore store, List<String> paths) {
        try {
            return Optional.of(FilesystemInfo.builder()
                    .paths(paths)
                    .totalBytes(store.getTotalSpace())
                    .usableBytes(store.getUsableSpace())
                    .build());
        } catch (IOException e) {
            log.debug("Could not read space for file store backing {}: {}", paths, e.getMessage());
            return Optional.empty();
        }
    }

    private PathStatus classify(Path path) {
        if (!pathProbe.isDirectory(path)) {
            return PathStatus.MISSING;
        }
        return pathProbe.isReadable(path) ? PathStatus.OK : PathStatus.UNREADABLE;
    }

    private List<String> configuredLibraryPaths() {
        return libraryPathRepository.findAllWithLibrary().stream()
                .map(entity -> entity.getPath())
                .toList();
    }

    private List<String> allKnownPaths() {
        List<String> paths = new ArrayList<>();
        paths.add(appProperties.getPathConfig());
        paths.add(appProperties.getBookdropFolder());
        paths.addAll(configuredLibraryPaths());
        return paths;
    }
}
