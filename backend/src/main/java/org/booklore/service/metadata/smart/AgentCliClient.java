package org.booklore.service.metadata.smart;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Injection seam over the external agent CLI, so resolution can be unit tested without spawning a
 * process or reaching the network.
 */
public interface AgentCliClient {

    /**
     * Runs a single non-interactive prompt.
     *
     * @return everything the agent printed, or empty if it could not be started, produced nothing,
     * or was killed before it finished.
     */
    Optional<String> run(String prompt);

    /**
     * Runs a plain CLI invocation such as {@code --version} or {@code models}, which answer from
     * local state in well under a second and so carry their own short timeout rather than the
     * minutes a web-searching prompt needs.
     *
     * @return everything printed, or empty if the CLI could not be started or exited non-zero.
     */
    Optional<String> runCommand(List<String> args, Duration timeout);
}
