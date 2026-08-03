package org.booklore.model.dto.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Operator-facing configuration of the agent used for smart metadata enrichment.
 * <p>
 * Which model to spend on a resolution is a judgement call that changes with results and cost, so it
 * belongs in the UI next to the other runtime settings rather than in a YAML file that requires a
 * restart. Whether the agent binary exists at all remains a deployment fact and stays in
 * {@link org.booklore.config.SmartEnrichmentProperties}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartEnrichmentSettings {

    private boolean enabled;

    /**
     * Model passed to the CLI. Blank leaves the CLI on its own configured default.
     */
    private String model;

    /**
     * Reasoning effort passed to the CLI: low, medium or high. Blank leaves the CLI default.
     */
    private String effort;

    /**
     * When true, the agent is told to search the web and read pages — accurate but slow and
     * quota-heavy, since every opened page's text enters the model context. When false (the default),
     * it answers from its own knowledge only: far cheaper, and it never fabricates a description
     * because it is told not to produce one without a page to quote.
     */
    private boolean deepSearch;

    /**
     * Sliding-window ceiling on agent invocations. A library-wide enrichment run would otherwise
     * spend quota as fast as the machine allows, so this is cost control rather than throughput
     * tuning — which is why it sits here, next to the model choice, and not in a YAML file.
     * <p>
     * Unset reads as {@link org.booklore.service.enrichment.steps.AgentRateLimiter#DEFAULT_LIMIT}
     * rather than as "unlimited": a settings row written before this field existed must not
     * silently uncap spending. A negative value is the explicit way to remove the cap.
     */
    private int maxAgentCallsPerHour;
}
