package org.booklore.service.metadata.smart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.settings.SmartEnrichmentSettings;
import org.booklore.model.dto.smart.ResolvedWorkIdentity;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentWorkIdentityResolver implements WorkIdentityResolver {

    /**
     * How much opening text to feed the agent. A couple of thousand characters covers the title page
     * and the first paragraphs — enough to identify the book — without turning the prompt (and its
     * token cost) into the whole first chapter.
     */
    private static final int EXCERPT_MAX_CHARS = 2500;

    private final AppSettingService appSettingService;
    private final AgentCliStatusService agentCliStatusService;
    private final AgentCliClient agentCliClient;
    private final WorkIdentityPromptBuilder promptBuilder;
    private final BookExcerptExtractor bookExcerptExtractor;
    private final ObjectMapper objectMapper;

    /**
     * Both halves matter: an operator can switch the feature on before installing the CLI, and a
     * CLI can be installed on an instance whose operator has not chosen to use it. Offering the
     * action in either case would produce a button that only ever fails.
     */
    @Override
    public boolean isAvailable() {
        SmartEnrichmentSettings settings = appSettingService.getAppSettings().getSmartEnrichmentSettings();
        return settings != null && settings.isEnabled() && agentCliStatusService.isBinaryAvailable();
    }

    @Override
    public Optional<ResolvedWorkIdentity> resolve(Book book) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        SmartEnrichmentSettings settings = appSettingService.getAppSettings().getSmartEnrichmentSettings();
        boolean deepSearch = settings != null && settings.isDeepSearch();
        String excerpt = bookExcerptExtractor.openingText(book, EXCERPT_MAX_CHARS).orElse(null);
        Optional<ResolvedWorkIdentity> first = resolveOnce(book, deepSearch, excerpt);
        if (deepSearch || first.isEmpty() || isIdentified(first.get())) {
            return first;
        }
        log.info("Quick resolution could not identify book {}; retrying once with bounded web search", book.getId());
        Optional<ResolvedWorkIdentity> fallback = resolveOnce(book, true, excerpt);
        return fallback.or(() -> first);
    }

    private Optional<ResolvedWorkIdentity> resolveOnce(Book book, boolean deepSearch, String excerpt) {
        String prompt = promptBuilder.build(book, deepSearch, excerpt);
        Optional<String> response = agentCliClient.run(prompt);
        if (response.isEmpty()) {
            log.warn("Work identity resolution produced no output for book {}", book.getId());
            return Optional.empty();
        }
        Optional<String> json = AgentResponseJsonExtractor.extractObject(response.get());
        if (json.isEmpty()) {
            log.warn("Work identity response for book {} contained no JSON object", book.getId());
            return Optional.empty();
        }
        // The JSON pulled out of the (possibly noisy) reply is logged separately from the raw reply
        // so a parse failure can be told apart from a bad extraction at a glance.
        log.info("Work identity JSON extracted for book {}:\n{}", book.getId(), json.get());
        try {
            ResolvedWorkIdentity identity = objectMapper.readValue(json.get(), ResolvedWorkIdentity.class);
            log.info("Work identity parsed for book {}: original='{}' by '{}', goodreads={}",
                    book.getId(), identity.originalTitle(), identity.originalAuthor(), identity.goodreadsUrl());
            return Optional.of(identity);
        } catch (Exception e) {
            log.warn("Could not parse work identity for book {}: {}", book.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isIdentified(ResolvedWorkIdentity identity) {
        return isUsable(identity.originalTitle())
                || isUsable(identity.editionTitle())
                || isUsable(identity.originalAuthor())
                || isUsable(identity.editionAuthor());
    }

    private boolean isUsable(String value) {
        return value != null && !value.isBlank();
    }
}
