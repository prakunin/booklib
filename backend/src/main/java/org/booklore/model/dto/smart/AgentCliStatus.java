package org.booklore.model.dto.smart;

import java.util.List;

/**
 * What the settings page needs to explain the agent's state: whether it is installed, whether it
 * has ever been signed in, and which models it offers.
 */
public record AgentCliStatus(
        boolean installed,
        String version,
        boolean authenticated,
        List<String> models
) {
}
