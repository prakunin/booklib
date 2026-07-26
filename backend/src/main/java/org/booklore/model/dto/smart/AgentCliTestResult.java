package org.booklore.model.dto.smart;

/**
 * Outcome of a real probe prompt: either what the agent answered, or why the run did not get there.
 */
public record AgentCliTestResult(
        boolean success,
        String message
) {
}
