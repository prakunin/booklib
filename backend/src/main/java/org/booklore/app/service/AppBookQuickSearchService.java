package org.booklore.app.service;

import lombok.RequiredArgsConstructor;
import org.booklore.app.dto.AppBookQuickSearchResult;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.repository.projection.BookSearchHitProjection;
import org.booklore.security.policy.ContentRestrictionSpecification;
import org.booklore.util.BookUtils;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppBookQuickSearchService {

    static final int MAX_RESULTS = 50;
    private static final int MAX_CANDIDATES = 2_000;
    private static final int MIN_TOKEN_LENGTH = 3;
    private static final int MAX_QUERY_TOKENS = 12;
    // With two words the only adjacent pair is the whole query, and a phrase is stricter than
    // the mandatory-word search that just came back empty, so the retry cannot add anything.
    private static final int MIN_TOKENS_FOR_PHRASE_FALLBACK = 3;
    private static final Pattern SEARCH_TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    // Native IN parameters cannot be empty. The predicate is disabled for admins,
    // so this impossible ID is only a syntactically valid placeholder.
    private static final List<Long> ADMIN_LIBRARY_SENTINEL = List.of(-1L);
    private static final Set<String> INNODB_DEFAULT_STOPWORDS = Set.of(
            "a", "about", "an", "are", "as", "at", "be", "by", "com", "de", "en", "for", "from",
            "how", "i", "in", "is", "it", "la", "of", "on", "or", "that", "the", "this", "to", "was",
            "what", "when", "where", "who", "will", "with", "und", "www");

    private final AuthenticationService authenticationService;
    private final BookRepository bookRepository;
    private final UserContentRestrictionRepository restrictionRepository;

    public List<AppBookQuickSearchResult> search(String rawQuery, Integer requestedLimit) {
        List<String> tokens = searchTokens(rawQuery);
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        boolean admin = user.getPermissions().isAdmin();
        Set<Long> accessibleLibraryIds = accessibleLibraryIds(user, admin);
        if (!admin && accessibleLibraryIds.isEmpty()) {
            return Collections.emptyList();
        }

        int limit = Math.clamp(requestedLimit == null ? MAX_RESULTS : requestedLimit, 1, MAX_RESULTS);
        Collection<Long> queryLibraryIds = admin ? ADMIN_LIBRARY_SENTINEL : accessibleLibraryIds;
        List<UserContentRestrictionEntity> restrictions = admin
                ? List.of()
                : restrictionRepository.findByUserId(user.getId());

        int candidateLimit = restrictions.isEmpty() ? limit : MAX_CANDIDATES;
        List<Long> candidateIds = findCandidateIds(tokens, admin, queryLibraryIds, candidateLimit);
        Set<Long> allowedIds = restrictions.isEmpty()
                ? new HashSet<>(candidateIds)
                : filterRestrictedIds(candidateIds, restrictions);

        LinkedHashSet<Long> visibleIds = new LinkedHashSet<>();
        for (Long candidateId : candidateIds) {
            if (allowedIds.contains(candidateId)) {
                visibleIds.add(candidateId);
                if (visibleIds.size() == limit) {
                    break;
                }
            }
        }

        if (visibleIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, BookEntity> books = bookRepository.findAllForSummaryByIds(visibleIds).stream()
                .collect(Collectors.toMap(BookEntity::getId, Function.identity()));
        return visibleIds.stream()
                .map(books::get)
                .filter(Objects::nonNull)
                .map(AppBookSearchResultMapper::toResult)
                .toList();
    }

    /**
     * Every word is mandatory, so one word the catalog never pairs with the rest - "цикл" in
     * "Цикл Дети времени" - empties a query that otherwise matches a whole series. The odd word out
     * cannot be found by asking which word is unknown: "цикл" matches 1710 books on its own, it just
     * never occurs next to the other two. So retry with the query's adjacent word pairs as optional
     * phrases. A book holding "дети времени" then outranks one that merely happens to contain
     * "цикл дети", and a word that pairs with nothing contributes no phrase at all.
     */
    private List<Long> findCandidateIds(List<String> tokens,
                                        boolean admin,
                                        Collection<Long> queryLibraryIds,
                                        int candidateLimit) {
        List<Long> candidateIds = searchCandidateIds(
                toBooleanQuery(tokens), admin, queryLibraryIds, candidateLimit);
        if (!candidateIds.isEmpty() || tokens.size() < MIN_TOKENS_FOR_PHRASE_FALLBACK) {
            return candidateIds;
        }
        return searchCandidateIds(
                toAdjacentPhraseQuery(tokens), admin, queryLibraryIds, candidateLimit);
    }

    private List<Long> searchCandidateIds(String searchQuery,
                                          boolean admin,
                                          Collection<Long> queryLibraryIds,
                                          int candidateLimit) {
        return bookRepository.searchBookIds(
                        searchQuery, !admin, queryLibraryIds, candidateLimit, 0).stream()
                .map(BookSearchHitProjection::getBookId)
                .toList();
    }

    static String toBooleanSearchQuery(String rawQuery) {
        return toBooleanQuery(searchTokens(rawQuery));
    }

    static List<String> searchTokens(String rawQuery) {
        String normalized = BookUtils.normalizeForSearch(rawQuery);
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }
        Matcher matcher = SEARCH_TOKEN.matcher(normalized);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= MIN_TOKEN_LENGTH && !INNODB_DEFAULT_STOPWORDS.contains(token)) {
                tokens.add(token);
                if (tokens.size() == MAX_QUERY_TOKENS) {
                    break;
                }
            }
        }
        return List.copyOf(tokens);
    }

    private static String toBooleanQuery(List<String> tokens) {
        return tokens.stream().map(token -> "+" + token + "*").collect(Collectors.joining(" "));
    }

    /**
     * Phrases carry no prefix operator: two words the user typed in full are what makes the pair
     * worth trusting, and a truncated last word would match nothing as a phrase anyway.
     */
    static String toAdjacentPhraseQuery(List<String> tokens) {
        return IntStream.range(1, tokens.size())
                .mapToObj(index -> "\"" + tokens.get(index - 1) + " " + tokens.get(index) + "\"")
                .collect(Collectors.joining(" "));
    }

    private Set<Long> filterRestrictedIds(Collection<Long> candidateIds,
                                          List<UserContentRestrictionEntity> restrictions) {
        if (candidateIds.isEmpty()) {
            return Collections.emptySet();
        }
        Specification<BookEntity> matchingIds = (root, query, cb) -> root.get("id").in(candidateIds);
        return bookRepository.findAll(ContentRestrictionSpecification.from(restrictions).and(matchingIds)).stream()
                .map(BookEntity::getId)
                .collect(Collectors.toSet());
    }

    private Set<Long> accessibleLibraryIds(BookLoreUser user, boolean admin) {
        if (admin || user.getAssignedLibraries() == null) {
            return Collections.emptySet();
        }
        return user.getAssignedLibraries().stream().map(Library::getId).collect(Collectors.toSet());
    }

}
