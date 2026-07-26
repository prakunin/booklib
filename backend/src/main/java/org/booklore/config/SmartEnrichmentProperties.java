package org.booklore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Deployment-level configuration for agent-assisted metadata enrichment: where the CLI lives, how
 * long it may run, and what it may see.
 * <p>
 * The agent CLI is an operator-provided binary that is not part of the shipped all-in-one image, so
 * an instance either has it on disk or it does not — a deployment fact rather than a preference.
 * What an operator does tune once it is installed — whether it is on, which model, how much
 * reasoning effort — lives in {@link org.booklore.model.dto.settings.SmartEnrichmentSettings} and is
 * edited in the UI. The values here seed that settings row the first time it is created.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "smart-enrichment")
public class SmartEnrichmentProperties {

    /**
     * Initial value of the UI toggle, applied only when no settings row exists yet.
     */
    private boolean enabled = false;

    /**
     * Agent CLI to invoke, resolved through {@code PATH} when not an absolute path.
     */
    private String binaryPath = "agy";

    /**
     * Initial model for the settings row. Blank leaves the CLI on its own configured default.
     */
    private String model;

    /**
     * Initial reasoning effort for the settings row: low, medium or high. Blank leaves the CLI default.
     */
    private String effort;

    /**
     * Initial value of the deep-web-search toggle. Off by default: the cheap model-only mode is the
     * sane default, and an operator opts into the slower, quota-heavy web run.
     */
    private boolean deepSearch = false;

    /**
     * Credential file the CLI writes when it is signed in. Its presence is what the settings page
     * reports as "authorised"; only the path is ever read, never the contents.
     */
    private String authTokenPath = System.getProperty("user.home") + "/.gemini/antigravity-cli/antigravity-oauth-token";

    /**
     * Hard ceiling on a single resolution run. A web-searching agent routinely takes tens of
     * seconds, so this is minutes rather than the seconds a local tool probe would use.
     */
    private Duration timeout = Duration.ofMinutes(4);

    /**
     * Directory the agent process runs in. Kept away from the library and the repository so the
     * agent has nothing of ours within reach even if a prompt goes wrong.
     */
    private String workingDirectory = System.getProperty("java.io.tmpdir");
}
