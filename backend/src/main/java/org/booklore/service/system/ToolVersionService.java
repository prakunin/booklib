package org.booklore.service.system;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.system.ToolsInfo;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool versions are only obtainable by running the binaries. They are baked into the image and
 * cannot change without a restart, so the first result is cached for the process lifetime: doing
 * this at startup would spawn processes nobody asked for, and doing it per request would fork twice
 * for a page view whose answer never changes.
 */
@Slf4j
@Service
@AllArgsConstructor
public class ToolVersionService {

    private final FileService fileService;
    private final ProcessRunner processRunner;

    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public ToolsInfo toolsInfo() {
        return ToolsInfo.builder()
                .ffprobeVersion(version("ffprobe", "-version").orElse(null))
                .kepubifyVersion(version("kepubify", "--version").orElse(null))
                .build();
    }

    private Optional<String> version(String binaryName, String versionFlag) {
        return cache.computeIfAbsent(binaryName, name -> {
            Path binary = fileService.findSystemFile(name);
            if (binary == null) {
                log.debug("{} binary not found; reporting no version", name);
                return Optional.empty();
            }
            return processRunner.firstLine(binary, versionFlag);
        });
    }
}
