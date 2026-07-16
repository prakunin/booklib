package org.booklore.service.system;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link TimeoutProcessRunner} against real, trivial shell processes. This is the
 * deliberate exception to "don't execute real binaries" elsewhere in the suite: the class exists
 * solely to bound process I/O, which can only be proven with an actual process.
 *
 * <p>Uses a short, test-only timeout (via the package-private constructor) instead of the
 * production default so the "never exits" case doesn't make the suite slow.
 */
class TimeoutProcessRunnerTest {

    private static final int TEST_TIMEOUT_SECONDS = 1;

    private final TimeoutProcessRunner runner = new TimeoutProcessRunner(TEST_TIMEOUT_SECONDS);

    @Nested
    class WhenTheProcessBehaves {

        @Test
        void returnsTheFirstLineWhenTheCommandPrintsOneAndExitsCleanly() {
            Optional<String> result = runner.firstLine(Path.of("sh"), "-c", "echo hello-version");

            assertThat(result).contains("hello-version");
        }

        @Test
        void returnsEmptyWhenTheCommandExitsNonZero() {
            Optional<String> result = runner.firstLine(Path.of("sh"), "-c", "exit 3");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class WhenTheProcessMisbehaves {

        @Test
        void returnsEmptyWithinTheTimeoutWhenTheProcessNeverPrintsOrExits() {
            Instant start = Instant.now();

            Optional<String> result = runner.firstLine(Path.of("sh"), "-c", "sleep 60");

            Duration elapsed = Duration.between(start, Instant.now());
            assertThat(result).isEmpty();
            // Well under the 60s sleep: proves the read is bounded, not just the exit wait.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        }

        @Test
        void returnsTheLineAlreadyReadWhenTheProcessLingersPastTheTimeoutBeforeExiting() {
            Instant start = Instant.now();

            Optional<String> result = runner.firstLine(Path.of("sh"), "-c", "echo prompt-line; sleep 60");

            Duration elapsed = Duration.between(start, Instant.now());
            assertThat(result).contains("prompt-line");
            assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        }

        @Test
        void returnsEmptyWhenTheBinaryDoesNotExist() {
            Optional<String> result = runner.firstLine(
                    Path.of("/definitely/not/a/real/binary-xyz-123"), "--version");

            assertThat(result).isEmpty();
        }
    }
}
