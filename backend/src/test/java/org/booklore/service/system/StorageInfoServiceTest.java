package org.booklore.service.system;

import org.booklore.config.AppProperties;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.PathStatus;
import org.booklore.repository.LibraryPathRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StorageInfoServiceTest {

    @Mock
    private PathProbe pathProbe;
    @Mock
    private LibraryPathRepository libraryPathRepository;
    @Mock
    private AppProperties appProperties;

    private StorageInfoService service;

    private LibraryPathEntity libraryPath(String path) {
        LibraryPathEntity entity = new LibraryPathEntity();
        entity.setPath(path);
        return entity;
    }

    @BeforeEach
    void setUp() {
        when(appProperties.getPathConfig()).thenReturn("/app/data");
        when(appProperties.getBookdropFolder()).thenReturn("/bookdrop");
        service = new StorageInfoService(pathProbe, libraryPathRepository, appProperties);
    }

    @Nested
    class Filesystems {

        @Test
        void groupsPathsSharingAFileStoreIntoOneEntry() throws IOException {
            // The real topology this was designed against: 3 library paths on one disk,
            // data and bookdrop on another.
            FileStore libraryStore = mock(FileStore.class);
            when(libraryStore.getTotalSpace()).thenReturn(2_000L);
            when(libraryStore.getUsableSpace()).thenReturn(1_600L);
            FileStore dataStore = mock(FileStore.class);
            when(dataStore.getTotalSpace()).thenReturn(900L);
            when(dataStore.getUsableSpace()).thenReturn(524L);

            when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of(
                    libraryPath("/books/fb2.Flibusta.Net"),
                    libraryPath("/books/Библиотека Остросюжетной Мистики (БОМ)"),
                    libraryPath("/books/Винчестер. Лучшие вестерны")));

            when(pathProbe.fileStore(any())).thenReturn(Optional.of(libraryStore));
            when(pathProbe.fileStore(Path.of("/app/data"))).thenReturn(Optional.of(dataStore));
            when(pathProbe.fileStore(Path.of("/bookdrop"))).thenReturn(Optional.of(dataStore));

            var filesystems = service.filesystems();

            assertThat(filesystems).hasSize(2);
            assertThat(filesystems)
                    .filteredOn(fs -> fs.getUsableBytes() == 1_600L)
                    .singleElement()
                    .satisfies(fs -> assertThat(fs.getPaths()).containsExactlyInAnyOrder(
                            "/books/fb2.Flibusta.Net",
                            "/books/Библиотека Остросюжетной Мистики (БОМ)",
                            "/books/Винчестер. Лучшие вестерны"));
            assertThat(filesystems)
                    .filteredOn(fs -> fs.getUsableBytes() == 524L)
                    .singleElement()
                    .satisfies(fs -> assertThat(fs.getPaths())
                            .containsExactlyInAnyOrder("/app/data", "/bookdrop"));
        }

        @Test
        void omitsFilesystemsThatCannotBeResolved() {
            when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of());
            when(pathProbe.fileStore(any())).thenReturn(Optional.empty());

            assertThat(service.filesystems()).isEmpty();
        }

        @Test
        void degradesToEmptyRatherThanPropagatingWhenTheRepositoryFails() {
            // Spring Data reports SQL failures as unchecked exceptions — exactly the case
            // this diagnostics tab exists for (DB unreachable).
            when(libraryPathRepository.findAllWithLibrary())
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));

            assertThat(service.filesystems()).isEmpty();
        }

        @Test
        void stillReportsDataAndBookdropFilesystemsWhenTheRepositoryFails() throws IOException {
            FileStore dataStore = mock(FileStore.class);
            when(dataStore.getTotalSpace()).thenReturn(900L);
            when(dataStore.getUsableSpace()).thenReturn(524L);
            when(libraryPathRepository.findAllWithLibrary())
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));
            when(pathProbe.fileStore(Path.of("/app/data"))).thenReturn(Optional.of(dataStore));
            when(pathProbe.fileStore(Path.of("/bookdrop"))).thenReturn(Optional.of(dataStore));

            var filesystems = service.filesystems();

            assertThat(filesystems).singleElement()
                    .satisfies(fs -> assertThat(fs.getPaths()).containsExactlyInAnyOrder("/app/data", "/bookdrop"));
        }

        @Test
        void skipsUnconfiguredPathConfigAndBookdropFolderRatherThanThrowing() {
            when(appProperties.getPathConfig()).thenReturn(null);
            when(appProperties.getBookdropFolder()).thenReturn(null);
            when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of());

            assertThat(service.filesystems()).isEmpty();
        }

        @Test
        void keepsResolvableFilesystemsWhenOneFails() throws IOException {
            FileStore dataStore = mock(FileStore.class);
            when(dataStore.getTotalSpace()).thenReturn(900L);
            when(dataStore.getUsableSpace()).thenReturn(524L);

            when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of());
            when(pathProbe.fileStore(Path.of("/app/data"))).thenReturn(Optional.of(dataStore));
            when(pathProbe.fileStore(Path.of("/bookdrop"))).thenReturn(Optional.empty());

            var filesystems = service.filesystems();

            assertThat(filesystems).singleElement()
                    .satisfies(fs -> assertThat(fs.getPaths()).containsExactly("/app/data"));
        }
    }

    @Nested
    class LibraryPaths {

        @Test
        void classifiesEachPathIndependently() {
            when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of(
                    libraryPath("/books/present"),
                    libraryPath("/books/gone"),
                    libraryPath("/books/locked")));

            when(pathProbe.isDirectory(Path.of("/books/present"))).thenReturn(true);
            when(pathProbe.isReadable(Path.of("/books/present"))).thenReturn(true);
            when(pathProbe.isDirectory(Path.of("/books/gone"))).thenReturn(false);
            when(pathProbe.isDirectory(Path.of("/books/locked"))).thenReturn(true);
            when(pathProbe.isReadable(Path.of("/books/locked"))).thenReturn(false);

            var paths = service.libraryPaths();

            assertThat(paths).extracting("path", "status").containsExactly(
                    org.assertj.core.groups.Tuple.tuple("/books/present", PathStatus.OK),
                    org.assertj.core.groups.Tuple.tuple("/books/gone", PathStatus.MISSING),
                    org.assertj.core.groups.Tuple.tuple("/books/locked", PathStatus.UNREADABLE));
        }

        @Test
        void degradesToEmptyRatherThanPropagatingWhenTheRepositoryFails() {
            when(libraryPathRepository.findAllWithLibrary())
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));

            assertThat(service.libraryPaths()).isEmpty();
        }
    }

    @Nested
    class Storage {

        @Test
        void reportsTheConfiguredDiskType() {
            when(appProperties.getDiskType()).thenReturn("NETWORK");

            assertThat(service.storageInfo().getDiskType()).isEqualTo("NETWORK");
        }
    }
}
