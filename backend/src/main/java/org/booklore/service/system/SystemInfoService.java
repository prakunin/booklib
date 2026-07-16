package org.booklore.service.system;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.system.ApplicationInfo;
import org.booklore.model.dto.system.DatabaseInfo;
import org.booklore.model.dto.system.FilesystemInfo;
import org.booklore.model.dto.system.LibraryPathInfo;
import org.booklore.model.dto.system.OsInfo;
import org.booklore.model.dto.system.RuntimeInfo;
import org.booklore.model.dto.system.StorageInfo;
import org.booklore.model.dto.system.SystemInfoDto;
import org.booklore.model.dto.system.ToolsInfo;
import org.booklore.service.VersionService;
import org.springframework.boot.SpringBootVersion;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
public class SystemInfoService {

    private final VersionService versionService;
    private final DataSource dataSource;
    private final StorageInfoService storageInfoService;
    private final ToolVersionService toolVersionService;

    public SystemInfoDto getSystemInfo() {
        // Fetched once and shared: filesystems() and libraryPaths() both classify the same set of
        // configured library paths, and previously each queried the repository independently.
        List<String> configuredPaths = configuredLibraryPaths();
        return SystemInfoDto.builder()
                .application(applicationInfo())
                .runtime(runtimeInfo())
                .os(osInfo())
                .database(databaseInfo())
                .storage(storageInfo())
                .filesystems(filesystems(configuredPaths))
                .libraryPaths(libraryPaths(configuredPaths))
                .tools(tools())
                .build();
    }

    private ApplicationInfo applicationInfo() {
        try {
            return ApplicationInfo.builder()
                    .version(versionService.getAppVersion())
                    .springBootVersion(SpringBootVersion.getVersion())
                    .build();
        } catch (Exception e) {
            // A diagnostics page must still render when the app-version lookup itself is broken.
            log.warn("Could not read application info for system info: {}", e.getMessage());
            return ApplicationInfo.builder().build();
        }
    }

    private RuntimeInfo runtimeInfo() {
        try {
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
        } catch (Exception e) {
            log.warn("Could not read runtime info for system info: {}", e.getMessage());
            return RuntimeInfo.builder().build();
        }
    }

    private OsInfo osInfo() {
        try {
            return OsInfo.builder()
                    .name(System.getProperty("os.name"))
                    .version(System.getProperty("os.version"))
                    .arch(System.getProperty("os.arch"))
                    .build();
        } catch (Exception e) {
            log.warn("Could not read OS info for system info: {}", e.getMessage());
            return OsInfo.builder().build();
        }
    }

    private DatabaseInfo databaseInfo() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return DatabaseInfo.builder()
                    .status("UP")
                    .vendor(metaData.getDatabaseProductName())
                    .version(metaData.getDatabaseProductVersion())
                    .build();
        } catch (Exception e) {
            // A diagnostics page must still render when the database is the thing that is broken.
            // Spring Data/JDBC can surface failures as unchecked exceptions, not just SQLException.
            log.warn("Could not read database metadata for system info: {}", e.getMessage());
            return DatabaseInfo.builder().status("DOWN").build();
        }
    }

    private StorageInfo storageInfo() {
        try {
            return storageInfoService.storageInfo();
        } catch (Exception e) {
            log.warn("Could not read storage info for system info: {}", e.getMessage());
            return StorageInfo.builder().build();
        }
    }

    private List<String> configuredLibraryPaths() {
        try {
            return storageInfoService.configuredLibraryPaths();
        } catch (Exception e) {
            log.warn("Could not read configured library paths for system info: {}", e.getMessage());
            return List.of();
        }
    }

    private List<FilesystemInfo> filesystems(List<String> configuredPaths) {
        try {
            return storageInfoService.filesystems(configuredPaths);
        } catch (Exception e) {
            log.warn("Could not read filesystem info for system info: {}", e.getMessage());
            return List.of();
        }
    }

    private List<LibraryPathInfo> libraryPaths(List<String> configuredPaths) {
        try {
            return storageInfoService.libraryPaths(configuredPaths);
        } catch (Exception e) {
            log.warn("Could not read library path info for system info: {}", e.getMessage());
            return List.of();
        }
    }

    private ToolsInfo tools() {
        try {
            return toolVersionService.toolsInfo();
        } catch (Exception e) {
            // A missing, failing, or hanging binary must not fail the whole response.
            log.warn("Could not read tool versions for system info: {}", e.getMessage());
            return ToolsInfo.builder().build();
        }
    }
}
