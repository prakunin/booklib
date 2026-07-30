package org.booklore.service.metadata.smart;

import org.booklore.config.SmartEnrichmentProperties;
import org.booklore.model.dto.smart.AgentCliStatus;
import org.booklore.model.dto.smart.AgentCliTestResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCliStatusServiceTest {

    private final SmartEnrichmentProperties properties = new SmartEnrichmentProperties();
    private final List<List<String>> invocations = new ArrayList<>();

    private AgentCliStatusService serviceAnswering(java.util.function.Function<List<String>, String> responder) {
        AgentCliClient client = new AgentCliClient() {
            @Override
            public Optional<String> run(String prompt) {
                throw new AssertionError("Status checks must not run a resolution prompt");
            }

            @Override
            public Optional<String> runCommand(List<String> args, Duration timeout) {
                invocations.add(args);
                return Optional.ofNullable(responder.apply(args));
            }
        };
        return new AgentCliStatusService(properties, client);
    }

    private String defaultResponses(List<String> args) {
        if (args.contains("--version")) {
            return "1.1.6";
        }
        if (args.contains("models")) {
            return "gemini-3.6-flash-high\ngemini-3.1-pro-low";
        }
        return "OK";
    }

    @Nested
    class Installation {

        @Test
        void reportsTheVersionWhenTheBinaryAnswers() {
            AgentCliStatus status = serviceAnswering(AgentCliStatusServiceTest.this::defaultResponses).status();

            assertThat(status.installed()).isTrue();
            assertThat(status.version()).isEqualTo("1.1.6");
        }

        @Test
        void reportsMissingWhenTheBinaryCannotBeRun() {
            AgentCliStatus status = serviceAnswering(_ -> null).status();

            assertThat(status.installed()).isFalse();
            assertThat(status.version()).isNull();
            assertThat(status.models()).isEmpty();
        }

        // Availability is checked whenever a book page renders; spawning a process each time would
        // be absurd, so the answer is cached.
        @Test
        void probesTheBinaryOnlyOnceAcrossRepeatedChecks() {
            AtomicInteger versionCalls = new AtomicInteger();
            AgentCliStatusService service = serviceAnswering(args -> {
                if (args.contains("--version")) {
                    versionCalls.incrementAndGet();
                }
                return defaultResponses(args);
            });

            service.isBinaryAvailable();
            service.isBinaryAvailable();
            service.status();

            assertThat(versionCalls.get()).isEqualTo(1);
        }
    }

    @Nested
    class Models {

        @Test
        void areListedOnePerLine() {
            assertThat(serviceAnswering(AgentCliStatusServiceTest.this::defaultResponses).listModels())
                    .containsExactly("gemini-3.6-flash-high", "gemini-3.1-pro-low");
        }

        // A banner or a warning line would otherwise become a selectable "model" in the dropdown.
        @Test
        void ignoreNoiseAroundTheList() {
            AgentCliStatusService service = serviceAnswering(args ->
                    args.contains("models") ? "Available models:\ngemini-3.6-flash-high\n\nRun agy --model <name>" : "1.1.6");

            assertThat(service.listModels()).containsExactly("gemini-3.6-flash-high");
        }
    }

    @Nested
    class Authentication {

        @Test
        void followsThePresenceOfTheCredentialFile(@TempDir Path tempDir) throws IOException {
            Path token = tempDir.resolve("antigravity-oauth-token");
            properties.setAuthTokenPath(token.toString());

            AgentCliStatusService service = serviceAnswering(AgentCliStatusServiceTest.this::defaultResponses);
            assertThat(service.status().authenticated()).isFalse();

            Files.writeString(token, "{}");
            assertThat(service.status().authenticated()).isTrue();
        }
    }

    @Nested
    class TestRun {

        @Test
        void returnsWhatTheAgentAnswered() {
            AgentCliTestResult result = serviceAnswering(AgentCliStatusServiceTest.this::defaultResponses).test();

            assertThat(result.success()).isTrue();
            assertThat(result.message()).isEqualTo("OK");
            assertThat(invocations).anyMatch(args -> args.contains("-p"));
        }

        @Test
        void doesNotPromptWhenTheBinaryIsMissing() {
            AgentCliTestResult result = serviceAnswering(_ -> null).test();

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("not found");
            assertThat(invocations).noneMatch(args -> args.contains("-p"));
        }

        // The two failures an operator can act on differ: an expired sign-in is fixed by running
        // the CLI once in a terminal, a quota or network failure is not.
        @Test
        void blamesSignInWhenNoCredentialFileExists() {
            properties.setAuthTokenPath("/nonexistent/antigravity-oauth-token");
            AgentCliTestResult result = serviceAnswering(args -> args.contains("--version") ? "1.1.6" : null).test();

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("not signed in");
        }
    }
}
