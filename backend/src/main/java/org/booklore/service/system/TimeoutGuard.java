package org.booklore.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounds a blocking call that may not throw when it fails. Some System-tab probes — a filesystem
 * syscall against a hung network mount, {@code DataSource.getConnection()} against a database that
 * is down — can block indefinitely without ever raising an exception, so the {@code catch
 * (Exception)} guards used elsewhere in this package are powerless against them; only a wall-clock
 * bound helps.
 *
 * <p>{@code work} runs on its own virtual thread. When it does not finish within the budget, this
 * returns {@link Optional#empty()} <strong>without cancelling or interrupting that thread</strong>.
 * A filesystem syscall or a JDBC call blocked in native/kernel code cannot be interrupted, so the
 * worker is simply abandoned and may stay parked forever. That is accepted, not a bug: virtual
 * threads are cheap, this endpoint is admin-only and low-traffic, and what matters is that {@code
 * run} — and the request calling it — returns. Do not "fix" this by adding cancellation, retries, or
 * a bounded thread pool; see {@link TimeoutProcessRunner} for the same reasoning applied to child
 * processes.
 */
@Slf4j
@Component
public class TimeoutGuard {

    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    private final int timeoutSeconds;

    public TimeoutGuard() {
        this(DEFAULT_TIMEOUT_SECONDS);
    }

    // Package-private: lets tests use a short timeout instead of sleeping through the real one.
    TimeoutGuard(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Runs {@code work} on a virtual thread and waits up to the configured budget.
     *
     * @param description a short, human-readable name for {@code work}, used only in log messages.
     * @return the result of {@code work}, or empty if the budget was exhausted first, or if {@code
     *     work} threw a (checked or unchecked) {@link Exception}. An {@link Error} thrown by {@code
     *     work} is rethrown here rather than swallowed — a diagnostics endpoint must not hide those.
     */
    public <T> Optional<T> run(String description, Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work);
        Thread.ofVirtual().start(task);
        try {
            return Optional.ofNullable(task.get(timeoutSeconds, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            log.warn("Timed out after {}s waiting for {}", timeoutSeconds, description);
            return Optional.empty();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            log.debug("{} failed: {}", description, cause != null ? cause.getMessage() : e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
