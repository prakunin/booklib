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
}
