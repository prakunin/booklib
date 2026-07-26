package org.booklore.service.metadata.smart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.SmartEnrichmentProperties;
import org.booklore.model.dto.smart.AgentCliStatus;
import org.booklore.model.dto.smart.AgentCliTestResult;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Reports whether the agent CLI is installed and signed in, and what it can be pointed at.
 * <p>
 * Two states look identical from the outside and are worth telling apart in the UI: a missing
 * binary, which no setting can fix, and an installed binary whose sign-in has expired, which the
 * operator fixes by running the CLI once in a terminal. Both otherwise surface as "enrichment
 * silently returns nothing".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCliStatusService {

    /**
     * {@code --version} and {@code models} answer from local state. A second is generous; the point
     * of the bound is that a wedged binary cannot stall the settings page.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * A test run is a real prompt against the real service, so it costs a round trip to the model.
     * Short compared to a resolution, because it asks for two characters.
     */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(90);

    private static final String TEST_PROMPT =
            "Reply with exactly the two characters OK. No punctuation, no explanation, no tool calls.";

    /**
     * How long an installed/authorised answer is reused. Availability is polled whenever a book is
     * opened, and spawning a process per open would be absurd; a few minutes of staleness after an
     * operator installs or re-authorises the CLI is not.
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * A little longer than {@link #PROBE_TIMEOUT}: {@code models} can block behind the CLI's own
     * lock while a resolution is running, and killing it too eagerly is what blanks the dropdown.
     */
    private static final Duration MODELS_TIMEOUT = Duration.ofSeconds(20);

    private final SmartEnrichmentProperties properties;
    private final AgentCliClient agentCliClient;

    private volatile CachedProbe cachedProbe;
    // The model catalogue is near-static, so the last good list is kept and reused when a later
    // `models` call comes back empty — which happens when the CLI is busy with a resolution and the
    // subcommand blocks on its lock. Without this the settings dropdown collapses to nothing
    // whenever the page is opened mid-run.
    private volatile List<String> cachedModels = List.of();
    private volatile long cachedModelsAtNanos;

    /**
     * Whether the binary is present. Cached, because the button that depends on it is rendered on
     * every book page.
     */
    public boolean isBinaryAvailable() {
        return probe().installed();
    }

    public AgentCliStatus status() {
        CachedProbe probe = probe();
        return new AgentCliStatus(
                probe.installed(),
                probe.version(),
                isAuthenticated(),
                probe.installed() ? listModels() : List.of());
    }

    /**
     * Runs a real, minimal prompt. This is the only check that proves the whole path works —
     * binary, credentials, network and quota — which is exactly why it is a button the operator
     * presses rather than something the page does on load.
     */
    public AgentCliTestResult test() {
        CachedProbe probe = probe();
        if (!probe.installed()) {
            return new AgentCliTestResult(false, "Agent CLI '" + properties.getBinaryPath() + "' was not found");
        }
        Optional<String> output = agentCliClient.runCommand(List.of("-p", TEST_PROMPT), TEST_TIMEOUT);
        if (output.isEmpty()) {
            return new AgentCliTestResult(false, isAuthenticated()
                    ? "The agent CLI produced no answer. Check its log and quota."
                    : "The agent CLI is not signed in. Run it once in a terminal to authorise it.");
        }
        return new AgentCliTestResult(true, output.get().strip());
    }

    /**
     * Presence of the credential file the CLI writes on sign-in. It is a proxy — a file can be
     * present and its token expired — so it downgrades the claim to "signed in at some point" and
     * leaves proof to {@link #test()}.
     */
    private boolean isAuthenticated() {
        String tokenPath = properties.getAuthTokenPath();
        if (tokenPath == null || tokenPath.isBlank()) {
            return false;
        }
        return Files.isReadable(Path.of(tokenPath));
    }

    public List<String> listModels() {
        List<String> cached = cachedModels;
        if (!cached.isEmpty() && System.nanoTime() - cachedModelsAtNanos < CACHE_TTL.toNanos()) {
            return cached;
        }
        Optional<String> output = agentCliClient.runCommand(List.of("models"), MODELS_TIMEOUT);
        if (output.isEmpty()) {
            log.warn("Agent 'models' produced no output (CLI busy or errored); reusing {} cached model(s)", cached.size());
            return cached;
        }
        List<String> models = Arrays.stream(output.get().split("\\R"))
                .map(String::strip)
                // The subcommand prints one model per line, but a stray banner or warning line would
                // otherwise end up in the dropdown as a selectable "model".
                .filter(line -> line.matches("[A-Za-z0-9._:-]{1,64}"))
                .distinct()
                .toList();
        if (models.isEmpty()) {
            log.warn("Agent 'models' returned {} chars but no recognisable model lines; reusing {} cached",
                    output.get().length(), cached.size());
            return cached;
        }
        cachedModels = models;
        cachedModelsAtNanos = System.nanoTime();
        log.info("Agent models refreshed ({}): {}", models.size(), models);
        return models;
    }

    private CachedProbe probe() {
        CachedProbe cached = cachedProbe;
        if (cached != null && !cached.isExpired()) {
            return cached;
        }
        Optional<String> version = agentCliClient.runCommand(List.of("--version"), PROBE_TIMEOUT);
        CachedProbe fresh = new CachedProbe(
                version.isPresent(),
                version.map(String::strip).orElse(null),
                System.nanoTime());
        cachedProbe = fresh;
        return fresh;
    }

    private record CachedProbe(boolean installed, String version, long probedAtNanos) {

        boolean isExpired() {
            return System.nanoTime() - probedAtNanos > CACHE_TTL.toNanos();
        }
    }
}
