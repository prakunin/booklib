package org.booklore.service.monitoring;

import org.booklore.model.dto.Library;
import org.booklore.model.dto.LibraryPath;
import org.booklore.service.watcher.LibraryFileEventProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LibraryWatchServiceTest {

    @TempDir
    Path tmp;

    LibraryWatchService service;
    LibraryFileEventProcessor processor;

    @BeforeEach
    void setup() {
        processor = mock(LibraryFileEventProcessor.class);
        service = new LibraryWatchService(processor);
        service.start();
    }

    @AfterEach
    void teardown() {
        try {
            service.stop();
        } catch (Exception _) {
            // best-effort teardown
        }
    }

    @Test
    void registerLibrary_registersAllDirectoriesUnderLibraryPath() throws Exception {
        Path root = tmp.resolve("libroot");
        Path a = root.resolve("a");
        Path b = a.resolve("b");
        Files.createDirectories(b);

        Library lib = mock(Library.class);
        LibraryPath lp = mock(LibraryPath.class);
        when(lp.getPath()).thenReturn(root.toString());
        when(lib.getPaths()).thenReturn(List.of(lp));
        when(lib.getId()).thenReturn(7L);
        when(lib.getName()).thenReturn("my-lib");
        when(lib.isWatch()).thenReturn(true);

        service.registerLibrary(lib);

        assertThat(service.isPathMonitored(root)).isTrue();
        assertThat(service.isPathMonitored(a)).isTrue();
        assertThat(service.isPathMonitored(b)).isTrue();
    }

    @Test
    void unregisterLibrary_removesRegisteredPaths() throws Exception {
        Path root = tmp.resolve("libroot2");
        Files.createDirectories(root);

        service.registerPath(root, 99L);
        assertThat(service.isPathMonitored(root)).isTrue();

        service.unregisterLibrary(99L);
        assertThat(service.isPathMonitored(root)).isFalse();
    }

    @Test
    void registerPath_succeedsForDirectories() throws Exception {
        Path dir = tmp.resolve("regdir");
        Files.createDirectories(dir);

        boolean registered = service.registerPath(dir, 55L);
        assertThat(registered).isTrue();
        assertThat(service.isPathMonitored(dir)).isTrue();
    }

    @Test
    void registerPath_failsForFilesAndMissingPaths() throws Exception {
        Path file = tmp.resolve("file.txt");
        Files.writeString(file, "content");

        assertThat(service.registerPath(file, 1L)).isFalse();

        Path missing = tmp.resolve("missing");
        assertThat(service.registerPath(missing, 1L)).isFalse();
    }

    @Test
    void registerPath_duplicateRegistrationReturnsFalse() throws Exception {
        Path dir = tmp.resolve("dupdir");
        Files.createDirectories(dir);

        assertThat(service.registerPath(dir, 10L)).isTrue();
        assertThat(service.registerPath(dir, 10L)).isFalse();
    }

    @Test
    void isPathMonitored_handlesNonNormalizedPaths() throws Exception {
        Path root = tmp.resolve("libroot-norm");
        Path sub = root.resolve("subdir");
        Files.createDirectories(sub);

        service.registerPath(sub, 1L);

        Path nonNormalized = root.resolve("subdir/../subdir/.");
        assertThat(service.isPathMonitored(nonNormalized)).isTrue();
    }

    @Test
    void getPathsForLibraries_returnsCorrectPaths() throws Exception {
        Path dir1 = tmp.resolve("lib1");
        Path dir2 = tmp.resolve("lib2");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);

        service.registerPath(dir1, 1L);
        service.registerPath(dir2, 2L);

        Set<Path> paths = service.getPathsForLibraries(Set.of(1L));
        assertThat(paths).containsExactly(dir1);
    }

    @Test
    void isRelevantBookFile_detectsBookExtensions() {
        assertThat(service.isRelevantBookFile(Paths.get("book.pdf"))).isTrue();
        assertThat(service.isRelevantBookFile(Paths.get("book.epub"))).isTrue();
        assertThat(service.isRelevantBookFile(Paths.get("notes.txt"))).isFalse();
    }

    @Test
    void isLibraryMonitored_reflectsWatchStatus() {
        Library lib = mock(Library.class);
        when(lib.getId()).thenReturn(5L);
        when(lib.isWatch()).thenReturn(true);
        when(lib.getPaths()).thenReturn(List.of());
        when(lib.getName()).thenReturn("test");

        service.registerLibrary(lib);
        assertThat(service.isLibraryMonitored(5L)).isTrue();

        service.unregisterLibrary(5L);
        assertThat(service.isLibraryMonitored(5L)).isFalse();
    }

    @Test
    void registerLibraries_registersOnlyWatchedLibrariesButTracksStatusForBoth() throws Exception {
        Path watchedRoot = tmp.resolve("watched-lib");
        Files.createDirectories(watchedRoot);
        Path unwatchedRoot = tmp.resolve("unwatched-lib");
        Files.createDirectories(unwatchedRoot);

        Library watched = mock(Library.class);
        LibraryPath watchedPath = mock(LibraryPath.class);
        when(watchedPath.getPath()).thenReturn(watchedRoot.toString());
        when(watched.getPaths()).thenReturn(List.of(watchedPath));
        when(watched.getId()).thenReturn(21L);
        when(watched.getName()).thenReturn("watched");
        when(watched.isWatch()).thenReturn(true);

        Library unwatched = mock(Library.class);
        when(unwatched.getId()).thenReturn(22L);
        when(unwatched.isWatch()).thenReturn(false);

        service.registerLibraries(List.of(watched, unwatched));

        assertThat(service.isPathMonitored(watchedRoot)).isTrue();
        assertThat(service.isLibraryMonitored(21L)).isTrue();
        assertThat(service.isLibraryMonitored(22L)).isFalse();
        assertThat(service.isPathMonitored(unwatchedRoot)).isFalse();
    }

    @Test
    void unregisterPath_removesARegisteredPathAndIsANoOpOtherwise() throws Exception {
        Path dir = tmp.resolve("unreg-dir");
        Files.createDirectories(dir);
        service.registerPath(dir, 30L);
        assertThat(service.isPathMonitored(dir)).isTrue();

        service.unregisterPath(dir);
        assertThat(service.isPathMonitored(dir)).isFalse();

        // Unregistering an already-unregistered (or never-registered) path is a no-op, not an error.
        service.unregisterPath(dir);
        assertThat(service.isPathMonitored(dir)).isFalse();
    }

    @Test
    void registerLibraryPaths_registersRootAndNestedDirectoriesWhenRootExists() throws Exception {
        Path root = tmp.resolve("lp-root");
        Path nested = root.resolve("nested");
        Files.createDirectories(nested);

        service.registerLibraryPaths(40L, root);

        assertThat(service.isPathMonitored(root)).isTrue();
        assertThat(service.isPathMonitored(nested)).isTrue();
    }

    @Test
    void registerLibraryPaths_isANoOpWhenRootDoesNotExist() {
        Path missingRoot = tmp.resolve("does-not-exist");

        service.registerLibraryPaths(41L, missingRoot);

        assertThat(service.isPathMonitored(missingRoot)).isFalse();
    }

    @Test
    void waitForEventsDrained_returnsTrueWithoutQueryingWhenLibraryIdsIsNullOrEmpty() {
        assertThat(service.waitForEventsDrained(null, 1000)).isTrue();
        assertThat(service.waitForEventsDrained(Set.of(), 1000)).isTrue();
        verifyNoInteractions(processor);
    }

    @Test
    void waitForEventsDrainedByPaths_returnsTrueImmediatelyWhenNoPendingEvents() {
        Path dir = tmp.resolve("drain-dir");
        when(processor.hasPendingEventsForPaths(Set.of(dir))).thenReturn(false);

        assertThat(service.waitForEventsDrainedByPaths(Set.of(dir), 1000)).isTrue();
    }

    @Test
    void waitForEventsDrainedByPaths_returnsFalseAfterTimeoutWhenEventsNeverDrain() {
        Path dir = tmp.resolve("stuck-dir");
        when(processor.hasPendingEventsForPaths(Set.of(dir))).thenReturn(true);

        assertThat(service.waitForEventsDrainedByPaths(Set.of(dir), 60)).isFalse();
    }

    @Test
    void waitForEventsDrained_delegatesToPathsForTheGivenLibraryIds() throws Exception {
        Path dir = tmp.resolve("delegate-dir");
        Files.createDirectories(dir);
        service.registerPath(dir, 50L);
        when(processor.hasPendingEventsForPaths(Set.of(dir))).thenReturn(false);

        assertThat(service.waitForEventsDrained(Set.of(50L), 1000)).isTrue();
    }
}
