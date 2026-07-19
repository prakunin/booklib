package org.booklore.service.system;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link TimeoutGuard} against real, genuinely blocking work (a live {@link Thread#sleep}
 * or an {@link CountDownLatch} that never opens) rather than mocks. This is the deliberate exception
 * to "don't sleep in tests" elsewhere in the suite: the class exists solely to bound work that can
 * hang without ever throwing, which can only be proven by making something actually hang.
 *
 * <p>Uses a short, test-only budget (via the package-private constructor) instead of the production
 * default so the "never finishes" cases don't make the suite slow.
 */
class TimeoutGuardTest {

    private static final int TEST_TIMEOUT_SECONDS = 1;

    private final TimeoutGuard guard = new TimeoutGuard(TEST_TIMEOUT_SECONDS);

    @Nested
    class WhenWorkFinishesInTime {

        @Test
        void returnsTheResultOfTheCallable() {
            Optional<String> result = guard.run("fast work", () -> "value");

            assertThat(result).contains("value");
        }

        @Test
        void returnsEmptyWhenTheCallableReturnsNullRatherThanThrowingNpe() {
            Optional<String> result = guard.run("null-returning work", () -> null);

            assertThat(result).isEmpty();
        }

        @Test
        void returnsEmptyWhenTheCallableThrowsACheckedOrUncheckedException() {
            Optional<String> result = guard.run("failing work", () -> {
                throw new IllegalStateException("boom");
            });

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class WhenWorkNeverFinishes {

        @Test
        void returnsEmptyWithinTheBudgetWhenTheCallableNeverReturns() {
            Instant start = Instant.now();

            Optional<String> result = guard.run("hung work", () -> {
                new CountDownLatch(1).await();
                return "never";
            });

            Duration elapsed = Duration.between(start, Instant.now());
            assertThat(result).isEmpty();
            // Proves the tight, budget-bound wait, not merely that the call eventually returns —
            // the CountDownLatch here never opens, so without a bound this would hang forever.
            assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
        }

        /**
         * Pins the exact scenario this class exists for: a filesystem syscall on a hung network
         * mount, or a JDBC call against a database that is down, blocks without ever throwing. The
         * {@link Timeout} annotation turns a regression (the guard failing to bound the wait) into a
         * fast failure instead of a wedged test run.
         */
        @Test
        @Timeout(10)
        @SuppressWarnings("java:S2925") // deliberate real hang (see class javadoc): proves the guard abandons a worker rather than waiting on it
        void abandonsTheWorkerAndReturnsPromptlyRatherThanWaitingForASixtySecondSleep() {
            Instant start = Instant.now();

            Optional<String> result = guard.run("very slow work", () -> {
                Thread.sleep(60_000);
                return "never";
            });

            Duration elapsed = Duration.between(start, Instant.now());
            assertThat(result).isEmpty();
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Nested
    class ErrorPropagation {

        @Test
        void rethrowsAnErrorThrownByTheCallableRatherThanSwallowingIt() {
            assertThatThrownBy(() -> guard.run("erroring work", () -> {
                throw new OutOfMemoryError("simulated");
            })).isInstanceOf(OutOfMemoryError.class);
        }
    }
}
