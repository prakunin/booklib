package org.booklore.service.metadata.smart;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.SmartEnrichmentSettings;
import org.booklore.model.dto.smart.ResolvedWorkIdentity;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkIdentityResolverTest {

    /**
     * Captured from a real run against the Russian translation of Montaigne's travel journal — the
     * case the feature exists for: no ISBN, no description, and a title that appears nowhere in an
     * English-language catalogue.
     */
    private static final String REAL_RESPONSE = """
            {
              "original_title": "Journal de voyage",
              "original_author": "Michel de Montaigne",
              "original_language": "fr",
              "first_published_year": 1774,
              "goodreads_url": "https://www.goodreads.com/book/show/104595.Montaigne_s_Travel_Journal",
              "reported_rating": 3.68,
              "description": "«Путевой дневник» Монтеня до настоящего времени был практически неизвестен в России.",
              "description_language": "ru",
              "description_source_url": "https://www.labirint.ru/books/700000/",
              "sources": [
                "https://www.goodreads.com/book/show/104595.Montaigne_s_Travel_Journal",
                "https://www.labirint.ru/books/700000/"
              ]
            }
            """;

    private final SmartEnrichmentSettings settings = SmartEnrichmentSettings.builder().enabled(false).build();
    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final AgentCliStatusService statusService = mock(AgentCliStatusService.class);
    private final BookExcerptExtractor excerptExtractor = mock(BookExcerptExtractor.class);

    private AgentWorkIdentityResolver resolverReturning(String response, AtomicReference<String> capturedPrompt) {
        when(appSettingService.getAppSettings())
                .thenReturn(AppSettings.builder().smartEnrichmentSettings(settings).build());
        when(statusService.isBinaryAvailable()).thenReturn(true);
        when(excerptExtractor.openingText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        AgentCliClient client = new AgentCliClient() {
            @Override
            public Optional<String> run(String prompt) {
                capturedPrompt.set(prompt);
                return Optional.ofNullable(response);
            }

            @Override
            public Optional<String> runCommand(List<String> args, Duration timeout) {
                throw new AssertionError("Resolution must not shell out to CLI subcommands");
            }
        };
        return new AgentWorkIdentityResolver(appSettingService, statusService, client,
                new WorkIdentityPromptBuilder(), excerptExtractor, new ObjectMapper());
    }

    private Book book() {
        return Book.builder()
                .id(42L)
                .title("Путевой дневник")
                .metadata(BookMetadata.builder()
                        .title("Путевой дневник. Путешествие Мишеля де Монтеня в Германию и Италию")
                        .authors(List.of("Монтень Мишель"))
                        .language("ru")
                        .build())
                .build();
    }

    @Nested
    class WhenEnabled {

        @Test
        void parsesTheRealAgentResponse() {
            settings.setEnabled(true);
            Optional<ResolvedWorkIdentity> resolved =
                    resolverReturning(REAL_RESPONSE, new AtomicReference<>()).resolve(book());

            assertThat(resolved).isPresent();
            ResolvedWorkIdentity identity = resolved.orElseThrow();
            assertThat(identity.originalTitle()).isEqualTo("Journal de voyage");
            assertThat(identity.originalAuthor()).isEqualTo("Michel de Montaigne");
            assertThat(identity.firstPublishedYear()).isEqualTo(1774);
            assertThat(identity.goodreadsUrl()).contains("104595");
            assertThat(identity.reportedRating()).isEqualTo(3.68);
            assertThat(identity.descriptionLanguage()).isEqualTo("ru");
            assertThat(identity.sources()).hasSize(2);
        }

        @Test
        void passesTheBooksOwnMetadataToTheAgent() {
            settings.setEnabled(true);
            AtomicReference<String> prompt = new AtomicReference<>();
            resolverReturning(REAL_RESPONSE, prompt).resolve(book());

            assertThat(prompt.get())
                    .contains("Путевой дневник. Путешествие Мишеля де Монтеня в Германию и Италию")
                    .contains("Монтень Мишель")
                    .contains("isbn: (missing)");
        }

        @Test
        void toleratesSurroundingChatter() {
            settings.setEnabled(true);
            String noisy = "Searching the web...\n```json\n" + REAL_RESPONSE + "\n```\nDone.";
            assertThat(resolverReturning(noisy, new AtomicReference<>()).resolve(book())).isPresent();
        }

        @Test
        void returnsEmptyWhenTheAgentProducesNoJson() {
            settings.setEnabled(true);
            assertThat(resolverReturning("I could not find this book.", new AtomicReference<>()).resolve(book()))
                    .isEmpty();
        }

        @Test
        void returnsEmptyWhenTheAgentCannotRun() {
            settings.setEnabled(true);
            assertThat(resolverReturning(null, new AtomicReference<>()).resolve(book())).isEmpty();
        }
    }

    @Nested
    class WhenDisabled {

        // The agent binary is not part of the shipped image, so a default instance must never even
        // attempt to spawn it.
        @Test
        void doesNotInvokeTheAgent() {
            AtomicReference<String> prompt = new AtomicReference<>();
            AgentWorkIdentityResolver resolver = resolverReturning(REAL_RESPONSE, prompt);

            assertThat(resolver.isAvailable()).isFalse();
            assertThat(resolver.resolve(book())).isEmpty();
            assertThat(prompt.get()).isNull();
        }
    }

    @Nested
    class WhenTheBinaryIsMissing {

        // An operator can switch the setting on before installing the CLI. Offering the action then
        // would produce a button whose only possible outcome is a failed run.
        @Test
        void reportsUnavailableEvenThoughTheSettingIsOn() {
            settings.setEnabled(true);
            AtomicReference<String> prompt = new AtomicReference<>();
            AgentWorkIdentityResolver resolver = resolverReturning(REAL_RESPONSE, prompt);
            when(statusService.isBinaryAvailable()).thenReturn(false);

            assertThat(resolver.isAvailable()).isFalse();
            assertThat(resolver.resolve(book())).isEmpty();
            assertThat(prompt.get()).isNull();
        }
    }
}
