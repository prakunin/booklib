package org.booklore.service.system;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.system.ToolsInfo;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tool versions are only obtainable by running the binaries. They are baked into the image and
 * cannot change without a restart, so a successful result is cached for the process lifetime: doing
 * this at startup would spawn processes nobody asked for, and doing it per request would fork twice
 * for a page view whose answer never changes. A failed probe is cached only when the failure is
 * <strong>permanent</strong> (see {@link #sanitizeVersion}) — a merely <strong>transient</strong>
 * failure (e.g. the host being loaded when the page is first viewed, or the process not exiting in
 * time) must not be reported as "not available" for the rest of the process's life; only a restart
 * could clear that, which is exactly what a diagnostics page exists to avoid.
 */
@Slf4j
@Service
@AllArgsConstructor
public class ToolVersionService {

    private static final int MAX_VERSION_LENGTH = 200;

    /**
     * A "-version" probe is expected to print a short line naming the tool and a dotted version
     * number (e.g. "ffprobe version 8.1.2", "kepubify v4.0.4"). This does not, and cannot, guarantee
     * that no secret ever reaches the response — the character class still permits arbitrary
     * alphanumeric runs, so a value that happens to contain digits and a dot slips through. It exists
     * to catch the realistic *accidental* leak shapes (a stray "KEY=VALUE" environment assignment, a
     * URL, a JDBC connection string), which contain characters outside this class, and to keep a
     * misbehaving binary's noise out of the response. Anyone with the ability to replace the binary
     * inside the image already has code execution and gains nothing from this tab that they don't
     * already have.
     */
    private static final Pattern PLAUSIBLE_VERSION = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9 ._+/(),-]*\\d+\\.\\d+[A-Za-z0-9 ._+/(),-]*$");

    private final FileService fileService;
    private final ProcessRunner processRunner;

    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();
    // Binaries whose output was read successfully but rejected by sanitizeVersion. That shape is a
    // property of the binary, not of one invocation, so unlike a transient probe failure it will not
    // fix itself on retry — caching it avoids forking a process on every page view forever.
    private final Set<String> permanentlyRejected = ConcurrentHashMap.newKeySet();

    public ToolsInfo toolsInfo() {
        return ToolsInfo.builder()
                .ffprobeVersion(version("ffprobe", "-version").orElse(null))
                .kepubifyVersion(version("kepubify", "--version").orElse(null))
                .build();
    }

    private Optional<String> version(String binaryName, String versionFlag) {
        if (cache.containsKey(binaryName)) {
            return cache.get(binaryName);
        }
        if (permanentlyRejected.contains(binaryName)) {
            return Optional.empty();
        }
        ProbeOutcome outcome = probe(binaryName, versionFlag);
        if (outcome.value().isPresent()) {
            // Only a success is cached for the process lifetime; see the class javadoc for why a
            // transient failure never is.
            cache.put(binaryName, outcome.value());
        } else if (outcome.permanent()) {
            permanentlyRejected.add(binaryName);
        }
        return outcome.value();
    }

    private ProbeOutcome probe(String binaryName, String versionFlag) {
        Path binary = fileService.findSystemFile(binaryName);
        if (binary == null) {
            log.debug("{} binary not found; reporting no version", binaryName);
            return ProbeOutcome.transientFailure();
        }
        Optional<String> rawLine = processRunner.firstLine(binary, versionFlag);
        if (rawLine.isEmpty()) {
            // ProcessRunner already reports empty for a missing/failing/timed-out/hung invocation —
            // all transient, none of them tell us anything permanent about the binary itself.
            return ProbeOutcome.transientFailure();
        }
        String sanitized = sanitizeVersion(rawLine.get());
        if (sanitized == null) {
            return ProbeOutcome.permanentRejection();
        }
        return ProbeOutcome.success(sanitized);
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

    private record ProbeOutcome(Optional<String> value, boolean permanent) {
        static ProbeOutcome success(String version) {
            return new ProbeOutcome(Optional.of(version), false);
        }

        static ProbeOutcome transientFailure() {
            return new ProbeOutcome(Optional.empty(), false);
        }

        static ProbeOutcome permanentRejection() {
            return new ProbeOutcome(Optional.empty(), true);
        }
    }
}
