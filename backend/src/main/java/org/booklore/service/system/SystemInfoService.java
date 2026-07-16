package org.booklore.service.system;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.system.ApplicationInfo;
import org.booklore.model.dto.system.OsInfo;
import org.booklore.model.dto.system.RuntimeInfo;
import org.booklore.model.dto.system.SystemInfoDto;
import org.booklore.service.VersionService;
import org.springframework.boot.SpringBootVersion;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

@Slf4j
@Service
@AllArgsConstructor
public class SystemInfoService {

    private final VersionService versionService;

    public SystemInfoDto getSystemInfo() {
        return SystemInfoDto.builder()
                .application(applicationInfo())
                .runtime(runtimeInfo())
                .os(osInfo())
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
}
