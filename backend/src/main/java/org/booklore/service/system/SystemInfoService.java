package org.booklore.service.system;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.system.ApplicationInfo;
import org.booklore.model.dto.system.DatabaseInfo;
import org.booklore.model.dto.system.OsInfo;
import org.booklore.model.dto.system.RuntimeInfo;
import org.booklore.model.dto.system.SystemInfoDto;
import org.booklore.service.VersionService;
import org.springframework.boot.SpringBootVersion;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

@Slf4j
@Service
@AllArgsConstructor
public class SystemInfoService {

    private final VersionService versionService;
    private final DataSource dataSource;
    private final StorageInfoService storageInfoService;

    public SystemInfoDto getSystemInfo() {
        return SystemInfoDto.builder()
                .application(applicationInfo())
                .runtime(runtimeInfo())
                .os(osInfo())
                .database(databaseInfo())
                .storage(storageInfoService.storageInfo())
                .filesystems(storageInfoService.filesystems())
                .libraryPaths(storageInfoService.libraryPaths())
                .build();
    }

    private ApplicationInfo applicationInfo() {
        return ApplicationInfo.builder()
                .version(versionService.getAppVersion())
                .springBootVersion(SpringBootVersion.getVersion())
                .build();
    }

    private RuntimeInfo runtimeInfo() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return RuntimeInfo.builder()
                .javaVersion(System.getProperty("java.version"))
                .javaVendor(System.getProperty("java.vendor"))
                .jvmUptimeMillis(ManagementFactory.getRuntimeMXBean().getUptime())
                .availableProcessors(Runtime.getRuntime().availableProcessors())
                .heapUsedBytes(heap.getUsed())
                // -1 when the JVM defines no maximum; passed through as-is.
                .heapMaxBytes(heap.getMax())
                .build();
    }

    private OsInfo osInfo() {
        return OsInfo.builder()
                .name(System.getProperty("os.name"))
                .version(System.getProperty("os.version"))
                .arch(System.getProperty("os.arch"))
                .build();
    }

    private DatabaseInfo databaseInfo() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return DatabaseInfo.builder()
                    .status("UP")
                    .vendor(metaData.getDatabaseProductName())
                    .version(metaData.getDatabaseProductVersion())
                    .build();
        } catch (SQLException e) {
            // A diagnostics page must still render when the database is the thing that is broken.
            log.warn("Could not read database metadata for system info: {}", e.getMessage());
            return DatabaseInfo.builder().status("DOWN").build();
        }
    }
}
