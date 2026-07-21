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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        // Benign default so blocks unrelated to the database don't have to know about it —
        // DatabaseBlock tests below override this per case.
        Connection defaultConnection = mock(Connection.class);
        DatabaseMetaData defaultMetaData = mock(DatabaseMetaData.class);
        lenient().when(dataSource.getConnection()).thenReturn(defaultConnection);
        lenient().when(defaultConnection.getMetaData()).thenReturn(defaultMetaData);

        // The production default budget (5s) is used here: every test in this class besides
        // DatabaseBlockTimeout resolves its mocks promptly, so this adds negligible overhead while
        // exercising the exact TimeoutGuard wiring production uses.
        service = new SystemInfoService(versionService, dataSource, storageInfoService, toolVersionService, new TimeoutGuard());
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

    /**
     * Unlike {@link DatabaseBlock}, these wire a real, short-budget {@link TimeoutGuard} instead of
     * the class-level {@link #service} (whose 5s production budget would make a genuinely hanging
     * mock slow to assert against). {@code dataSource.getConnection()} here truly never returns —
     * proof this is bounding wall-clock time, not just mapping an already-thrown exception to DOWN.
     */
    @Nested
    class DatabaseBlockTimeout {

        @Test
        void reportsDownWithinTheBudgetWhenConnectionAcquisitionNeverReturns() throws Exception {
            TimeoutGuard shortGuard = new TimeoutGuard(1);
            SystemInfoService boundedService =
                    new SystemInfoService(versionService, dataSource, storageInfoService, toolVersionService, shortGuard);
            when(dataSource.getConnection()).thenAnswer(invocation -> {
                new CountDownLatch(1).await();
                return mock(Connection.class);
            });
            when(versionService.getAppVersion()).thenReturn("v1.2.3");

            Instant start = Instant.now();
            var info = boundedService.getSystemInfo();
            Duration elapsed = Duration.between(start, Instant.now());

            assertThat(info.getDatabase().getStatus()).isEqualTo("DOWN");
            // Bounded by the 1s guard, not the CountDownLatch that never opens.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
            // The whole point of the tab: the rest still renders when the DB call hangs.
            assertThat(info.getApplication().getVersion()).isEqualTo("v1.2.3");
            assertThat(info.getRuntime().getAvailableProcessors()).isPositive();
        }

        @Test
        @SuppressWarnings("java:S2925") // simulates a genuinely slow (but finite) DB call so the guard's real wall-clock timeout can be exercised; not a wait-for-condition
        void aSlowConfiguredLibraryPathsCallDoesNotEatTheDatabaseBlocksBudget() throws Exception {
            TimeoutGuard shortGuard = new TimeoutGuard(1);
            SystemInfoService boundedService =
                    new SystemInfoService(versionService, dataSource, storageInfoService, toolVersionService, shortGuard);
            // Slower than the 1s guard, but finite: this must still time out at ~1s on its own
            // budget rather than consuming (or being consumed by) the database block's budget.
            when(storageInfoService.configuredLibraryPaths()).thenAnswer(invocation -> {
                Thread.sleep(1500);
                return List.of();
            });
            Connection connection = mock(Connection.class);
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getMetaData()).thenReturn(metaData);
            when(metaData.getDatabaseProductName()).thenReturn("MariaDB");
            when(metaData.getDatabaseProductVersion()).thenReturn("12.3.2-MariaDB-ubu2404");

            Instant start = Instant.now();
            var info = boundedService.getSystemInfo();
            Duration elapsed = Duration.between(start, Instant.now());

            // If the two blocks shared one deadline, the database call would start with (close to)
            // no budget left and report DOWN. It does not: its own, independent 1s budget applies.
            assertThat(info.getDatabase().getStatus()).isEqualTo("UP");
            assertThat(info.getDatabase().getVendor()).isEqualTo("MariaDB");
            // ~1s (configuredLibraryPaths' own timeout) plus a fast database call — comfortably
            // under 2x the guard, which is what a shared/consumed budget would look like instead.
            assertThat(elapsed).isLessThan(Duration.ofMillis(2500));
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
            verify(storageInfoService, times(1)).configuredLibraryPaths();
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
