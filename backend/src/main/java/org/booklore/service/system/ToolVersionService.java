package org.booklore.service.system;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.system.ToolsInfo;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tool versions are only obtainable by running the binaries. They are baked into the image and
 * cannot change without a restart, so a successful result is cached for the process lifetime: doing
 * this at startup would spawn processes nobody asked for, and doing it per request would fork twice
 * for a page view whose answer never changes. A failed probe is deliberately <strong>not</strong>
 * cached — a transient failure (e.g. the host being loaded when the page is first viewed) must not
 * be reported as "not available" for the rest of the process's life; only a restart could clear
 * that, which is exactly what a diagnostics page exists to avoid.
 */
@Slf4j
@Service
@AllArgsConstructor
public class ToolVersionService {

    private static final int MAX_VERSION_LENGTH = 200;

    /**
     * A "-version" probe is expected to print a short line naming the tool and a dotted version
     * number (e.g. "ffprobe version 8.1.2", "kepubify v4.0.4"). Constraining accepted output to
     * that shape, instead of trusting whatever the process wrote, means a replaced or misbehaving
     * binary that prints a secret, a "KEY=VALUE" environment assignment, or a URL can never reach
     * the response: none of those match this pattern, so they are reported as "not available"
     * instead of being echoed back to the client verbatim.
     */
    private static final Pattern PLAUSIBLE_VERSION = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9 ._+/(),-]*\\d+\\.\\d+[A-Za-z0-9 ._+/(),-]*$");

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
        Optional<String> cached = cache.get(binaryName);
        if (cached != null) {
            return cached;
        }
        Optional<String> result = probe(binaryName, versionFlag);
        // Only a success is cached; see the class javadoc for why a failure never is.
        if (result.isPresent()) {
            cache.put(binaryName, result);
        }
        return result;
    }

    private Optional<String> probe(String binaryName, String versionFlag) {
        Path binary = fileService.findSystemFile(binaryName);
        if (binary == null) {
            log.debug("{} binary not found; reporting no version", binaryName);
            return Optional.empty();
        }
        return processRunner.firstLine(binary, versionFlag)
                .map(this::sanitizeVersion)
                .filter(Objects::nonNull);
    }

    private String sanitizeVersion(String rawLine) {
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_VERSION_LENGTH) {
            return null;
        }
        if (!PLAUSIBLE_VERSION.matcher(trimmed).matches()) {
            log.warn("Discarding tool output that does not look like a version line ({} chars)", trimmed.length());
            return null;
        }
        return trimmed;
    }
}
