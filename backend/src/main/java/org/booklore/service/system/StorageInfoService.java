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

    StorageInfo storageInfo() {
        return StorageInfo.builder()
                .diskType(appProperties.getDiskType())
                .build();
    }

    /**
     * Called by {@link SystemInfoService} with the {@link #configuredLibraryPaths()} it already
     * fetched once per request and shares with {@link #filesystems(List)}, instead of this query
     * running twice for one System-tab view.
     */
    List<LibraryPathInfo> libraryPaths(List<String> configuredPaths) {
        List<LibraryPathInfo> result = new ArrayList<>();
        for (String path : configuredPaths) {
            // A path that cannot be resolved (e.g. a NULL row read back as null, since
            // library_path.path has no NOT NULL constraint at the schema level) must degrade only
            // itself, not the classification of every other path. It is dropped from this list
            // entirely rather than reported with some placeholder status; see filesystems(List)'s
            // javadoc for why the same choice is made there.
            toPath(path).ifPresent(resolved -> result.add(LibraryPathInfo.builder()
                    .path(path)
                    .status(classify(resolved))
                    .build()));
        }
        return result;
    }

    /**
     * Free space is reported per distinct file store, not per path: several library paths commonly
     * share one disk, and repeating the same figure for each would be noise. Paths that cannot be
     * resolved contribute nothing here — and they do not surface anywhere else either:
     * {@link #libraryPaths(List)} silently drops the same unresolvable row rather than reporting it
     * with a distinct status. That is a deliberate choice (one bad row must not take out the whole
     * response), not an oversight; a caller cannot use either list to tell "not configured" apart
     * from "configured but unresolvable".
     */
    List<FilesystemInfo> filesystems(List<String> configuredPaths) {
        Map<FileStore, List<String>> pathsByStore = new LinkedHashMap<>();
        for (String path : allKnownPaths(configuredPaths)) {
            toPath(path).flatMap(pathProbe::fileStore)
                    .ifPresent(fileStore -> pathsByStore.computeIfAbsent(fileStore, k -> new ArrayList<>()).add(path));
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
        } catch (Exception e) {
            // getTotalSpace/getUsableSpace declare only IOException, but a broken or unusual
            // filesystem provider can surface unchecked failures too; catching narrowly would let
            // one bad store escape this per-store guard and discard every resolvable filesystem.
            log.debug("Could not read space for file store backing {}: {}", paths, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * An empty {@link PathProbe} result means the underlying syscall did not answer within its time
     * budget (e.g. a hung network mount), not that the path was checked and found missing — so it
     * classifies as {@link PathStatus#UNKNOWN}, never silently falls through to {@link
     * PathStatus#MISSING}.
     */
    private PathStatus classify(Path path) {
        Optional<Boolean> isDirectory = pathProbe.isDirectory(path);
        if (isDirectory.isEmpty()) {
            return PathStatus.UNKNOWN;
        }
        boolean directory = isDirectory.get();
        if (!directory) {
            return PathStatus.MISSING;
        }
        Optional<Boolean> isReadable = pathProbe.isReadable(path);
        if (isReadable.isEmpty()) {
            return PathStatus.UNKNOWN;
        }
        boolean readable = isReadable.get();
        return readable ? PathStatus.OK : PathStatus.UNREADABLE;
    }

    /**
     * Configured library paths as plain strings. Deliberately uses
     * {@link LibraryPathRepository#findAllPaths()} rather than {@link LibraryPathRepository#findAllWithLibrary()}:
     * this feature never reads the associated {@code LibraryEntity} that the latter's
     * {@code JOIN FETCH} pulls in (that association exists for {@code LibraryHealthService}).
     *
     * <p>Package-private so {@link SystemInfoService} can fetch this once per request and pass the
     * same list into both {@link #libraryPaths(List)} and {@link #filesystems(List)}, instead of
     * this query running twice for one System-tab view.
     */
    List<String> configuredLibraryPaths() {
        try {
            return libraryPathRepository.findAllPaths();
        } catch (Exception e) {
            // The DB being unreachable must not take down filesystem/library-path reporting,
            // which is often exactly what an operator needs to see when the DB is the problem.
            log.warn("Could not read configured library paths for system info: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> allKnownPaths(List<String> configuredPaths) {
        List<String> paths = new ArrayList<>();
        // pathConfig/bookdropFolder may be unconfigured; Path.of(null) would NPE and take
        // the whole filesystem listing down with it, so skip whichever is missing.
        if (appProperties.getPathConfig() != null) {
            paths.add(appProperties.getPathConfig());
        }
        if (appProperties.getBookdropFolder() != null) {
            paths.add(appProperties.getBookdropFolder());
        }
        paths.addAll(configuredPaths);
        return paths;
    }

    /**
     * Resolves a raw, possibly database-sourced path string to a {@link Path}, isolating the
     * failure to this one path rather than the whole caller. {@code Path.of(null)} throws
     * {@link NullPointerException}; a malformed string can throw {@code InvalidPathException}.
     * Either way, one bad path must not erase every other path's status.
     */
    private Optional<Path> toPath(String rawPath) {
        try {
            return Optional.of(Path.of(rawPath));
        } catch (Exception e) {
            log.warn("Could not resolve a configured path for system info: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
