package org.booklore.service.system;

import org.booklore.service.VersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemInfoServiceTest {

    @Mock
    private VersionService versionService;

    @Mock
    private DataSource dataSource;

    private SystemInfoService service;

    @BeforeEach
    void setUp() throws Exception {
        // Benign default so blocks unrelated to the database don't have to know about it;
        // DatabaseBlock tests below override this per-case.
        Connection defaultConnection = mock(Connection.class);
        DatabaseMetaData defaultMetaData = mock(DatabaseMetaData.class);
        lenient().when(dataSource.getConnection()).thenReturn(defaultConnection);
        lenient().when(defaultConnection.getMetaData()).thenReturn(defaultMetaData);

        service = new SystemInfoService(versionService, dataSource);
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
    }
}
