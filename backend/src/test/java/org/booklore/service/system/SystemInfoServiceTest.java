package org.booklore.service.system;

import org.booklore.model.dto.system.LibraryPathInfo;
import org.booklore.model.dto.system.StorageInfo;
import org.booklore.model.dto.system.ToolsInfo;
import org.booklore.service.VersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemInfoServiceTest {

    @Mock
    private VersionService versionService;

    @Mock
    private DataSource dataSource;

    @Mock
    private StorageInfoService storageInfoService;

    @Mock
    private ToolVersionService toolVersionService;

    private SystemInfoService service;

    @BeforeEach
    void setUp() throws Exception {
        // Benign default so blocks unrelated to the database don't have to know about it;
        // DatabaseBlock tests below override this per-case.
        Connection defaultConnection = mock(Connection.class);
        DatabaseMetaData defaultMetaData = mock(DatabaseMetaData.class);
        lenient().when(dataSource.getConnection()).thenReturn(defaultConnection);
        lenient().when(defaultConnection.getMetaData()).thenReturn(defaultMetaData);

        service = new SystemInfoService(versionService, dataSource, storageInfoService, toolVersionService);
    }

    @Nested
    class ApplicationBlock {

        @Test
        void reportsTheAppVersionFromVersionService() {
            when(versionService.getAppVersion()).thenReturn("v1.2.3");

            assertThat(service.getSystemInfo().getApplication().getVersion()).isEqualTo("v1.2.3");
        }

        @Test
        void reportsANonBlankSpringBootVersion() {
            assertThat(service.getSystemInfo().getApplication().getSpringBootVersion()).isNotBlank();
        }

        @Test
        void degradesToEmptyBuilderWithoutFailingTheResponseWhenVersionServiceFails() {
            // versionService.getAppVersion() is the only unchecked-throw seam in this block; an
            // unguarded throw here previously aborted the entire response, including database,
            // runtime and os, which have nothing to do with the app version.
            when(versionService.getAppVersion()).thenThrow(new RuntimeException("boom"));

            var info = service.getSystemInfo();

            assertThat(info.getApplication()).isNotNull();
            assertThat(info.getApplication().getVersion()).isNull();
            // The rest of the response must still populate.
            assertThat(info.getOs().getName()).isNotBlank();
            assertThat(info.getRuntime().getAvailableProcessors()).isPositive();
            assertThat(info.getDatabase().getStatus()).isEqualTo("UP");
        }
    }

    @Nested
    class RuntimeBlock {

        @Test
        void reportsJvmFacts() {
            var runtime = service.getSystemInfo().getRuntime();

            assertThat(runtime.getJavaVersion()).isNotBlank();
            assertThat(runtime.getJavaVendor()).isNotBlank();
            assertThat(runtime.getJvmUptimeMillis()).isNotNegative();
            assertThat(runtime.getAvailableProcessors()).isPositive();
            assertThat(runtime.getHeapUsedBytes()).isPositive();
            assertThat(runtime.getHeapMaxBytes()).isNotZero();
        }
    }

    @Nested
    class OsBlock {

        @Test
        void reportsOsFacts() {
            var os = service.getSystemInfo().getOs();

            assertThat(os.getName()).isNotBlank();
            assertThat(os.getVersion()).isNotBlank();
            assertThat(os.getArch()).isNotBlank();
        }
    }

    @Nested
    class DatabaseBlock {

        @Test
        void reportsVendorAndVersionWhenReachable() throws Exception {
            Connection connection = mock(Connection.class);
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getMetaData()).thenReturn(metaData);
            when(metaData.getDatabaseProductName()).thenReturn("MariaDB");
            when(metaData.getDatabaseProductVersion()).thenReturn("12.3.2-MariaDB-ubu2404");

            var database = service.getSystemInfo().getDatabase();

            assertThat(database.getStatus()).isEqualTo("UP");
            assertThat(database.getVendor()).isEqualTo("MariaDB");
            assertThat(database.getVersion()).isEqualTo("12.3.2-MariaDB-ubu2404");
        }

        @Test
        void reportsDownWithoutFailingTheResponseWhenUnreachable() throws Exception {
            when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
            when(versionService.getAppVersion()).thenReturn("v1.2.3");

            var info = service.getSystemInfo();

            assertThat(info.getDatabase().getStatus()).isEqualTo("DOWN");
            assertThat(info.getDatabase().getVendor()).isNull();
            assertThat(info.getDatabase().getVersion()).isNull();
            // The whole point of the tab: the rest still renders when the DB is gone.
            assertThat(info.getApplication().getVersion()).isEqualTo("v1.2.3");
            assertThat(info.getRuntime().getAvailableProcessors()).isPositive();
        }

        @Test
        void reportsDownWithoutFailingTheResponseOnUncheckedFailure() throws Exception {
            // Spring Data/JDBC connection pools (e.g. HikariCP) surface outages as unchecked
            // exceptions, not SQLException — the old narrow catch let these escape.
            when(dataSource.getConnection()).thenThrow(new DataAccessResourceFailureException("pool exhausted"));
            when(versionService.getAppVersion()).thenReturn("v1.2.3");

            var info = service.getSystemInfo();

            assertThat(info.getDatabase().getStatus()).isEqualTo("DOWN");
            assertThat(info.getApplication().getVersion()).isEqualTo("v1.2.3");
        }
    }

    @Nested
    class StorageBlockIsolation {

        @Test
        void filesystemsAndLibraryPathsDegradeToEmptyWhenStorageInfoServiceFails() {
            when(storageInfoService.filesystems(any()))
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));
            when(storageInfoService.libraryPaths(any()))
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));
            when(versionService.getAppVersion()).thenReturn("v1.2.3");

            var info = service.getSystemInfo();

            assertThat(info.getFilesystems()).isEmpty();
            assertThat(info.getLibraryPaths()).isEmpty();
            // The rest of the response must still populate.
            assertThat(info.getApplication().getVersion()).isEqualTo("v1.2.3");
            assertThat(info.getRuntime().getAvailableProcessors()).isPositive();
            assertThat(info.getOs().getName()).isNotBlank();
        }

        @Test
        void storageDegradesToEmptyBuilderWhenStorageInfoServiceFails() {
            when(storageInfoService.storageInfo()).thenThrow(new RuntimeException("unexpected"));

            var storage = service.getSystemInfo().getStorage();

            assertThat(storage).isNotNull();
            assertThat(storage.getDiskType()).isNull();
        }

        @Test
        void storageBlocksSucceedIndependentlyWhenOnlyOneFails() {
            when(storageInfoService.filesystems(any()))
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));
            when(storageInfoService.libraryPaths(any())).thenReturn(List.of(
                    LibraryPathInfo.builder().path("/books").build()));
            when(storageInfoService.storageInfo()).thenReturn(StorageInfo.builder().diskType("LOCAL").build());

            var info = service.getSystemInfo();

            assertThat(info.getFilesystems()).isEmpty();
            assertThat(info.getLibraryPaths()).extracting(LibraryPathInfo::getPath).containsExactly("/books");
            assertThat(info.getStorage().getDiskType()).isEqualTo("LOCAL");
        }

        @Test
        void fetchesConfiguredLibraryPathsOnceAndSharesThemWithBothBlocks() {
            when(storageInfoService.configuredLibraryPaths()).thenReturn(List.of("/books/shared"));
            when(storageInfoService.libraryPaths(List.of("/books/shared"))).thenReturn(List.of(
                    LibraryPathInfo.builder().path("/books/shared").build()));
            when(storageInfoService.filesystems(List.of("/books/shared"))).thenReturn(List.of());

            var info = service.getSystemInfo();

            assertThat(info.getLibraryPaths()).extracting(LibraryPathInfo::getPath).containsExactly("/books/shared");
            // configuredLibraryPaths() is fetched exactly once per request, not once per block.
            verify(storageInfoService, org.mockito.Mockito.times(1)).configuredLibraryPaths();
        }

        @Test
        void configuredLibraryPathsFailureDegradesToEmptyWithoutFailingTheResponse() {
            when(storageInfoService.configuredLibraryPaths())
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));
            when(versionService.getAppVersion()).thenReturn("v1.2.3");

            var info = service.getSystemInfo();

            assertThat(info.getApplication().getVersion()).isEqualTo("v1.2.3");
        }
    }

    @Nested
    class ToolsBlock {

        @Test
        void reportsVersionsFromToolVersionService() {
            when(toolVersionService.toolsInfo()).thenReturn(
                    ToolsInfo.builder().ffprobeVersion("ffprobe version 8.1.2").kepubifyVersion("kepubify v4.0.4").build());

            var tools = service.getSystemInfo().getTools();

            assertThat(tools.getFfprobeVersion()).isEqualTo("ffprobe version 8.1.2");
            assertThat(tools.getKepubifyVersion()).isEqualTo("kepubify v4.0.4");
        }

        @Test
        void degradesToEmptyBuilderWithoutFailingTheResponseWhenToolVersionServiceFails() {
            when(toolVersionService.toolsInfo()).thenThrow(new RuntimeException("boom"));
            when(versionService.getAppVersion()).thenReturn("v1.2.3");

            var info = service.getSystemInfo();

            assertThat(info.getTools()).isNotNull();
            assertThat(info.getTools().getFfprobeVersion()).isNull();
            assertThat(info.getTools().getKepubifyVersion()).isNull();
            // The rest of the response must still populate.
            assertThat(info.getApplication().getVersion()).isEqualTo("v1.2.3");
            assertThat(info.getRuntime().getAvailableProcessors()).isPositive();
        }
    }
}
