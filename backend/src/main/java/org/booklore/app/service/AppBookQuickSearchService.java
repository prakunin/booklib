package org.booklore.app.service;

import lombok.RequiredArgsConstructor;
import org.booklore.app.dto.AppBookQuickSearchResult;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppBookQuickSearchService {

    static final int MAX_RESULTS = 50;
    private static final int MAX_CANDIDATES = 2_000;
    private static final int MIN_TOKEN_LENGTH = 3;
    private static final int MAX_QUERY_TOKENS = 12;
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
        String searchQuery = toBooleanSearchQuery(rawQuery);
        if (searchQuery.isEmpty()) {
            return Collections.emptyList();
        }

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        boolean admin = user.getPermissions().isAdmin();
        Set<Long> accessibleLibraryIds = accessibleLibraryIds(user, admin);
        if (!admin && accessibleLibraryIds.isEmpty()) {
            return Collections.emptyList();
        }

        int limit = Math.min(Math.max(requestedLimit == null ? MAX_RESULTS : requestedLimit, 1), MAX_RESULTS);
        Collection<Long> queryLibraryIds = admin ? ADMIN_LIBRARY_SENTINEL : accessibleLibraryIds;
        List<UserContentRestrictionEntity> restrictions = admin
                ? List.of()
                : restrictionRepository.findByUserId(user.getId());

        int candidateLimit = restrictions.isEmpty() ? limit : MAX_CANDIDATES;
        List<Long> candidateIds = bookRepository.searchBookIds(
                        searchQuery, !admin, queryLibraryIds, candidateLimit, 0).stream()
                .map(BookSearchHitProjection::getBookId)
                .toList();
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
                .map(this::toResult)
                .toList();
    }

    static String toBooleanSearchQuery(String rawQuery) {
        String normalized = BookUtils.normalizeForSearch(rawQuery);
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        Matcher matcher = SEARCH_TOKEN.matcher(normalized);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= MIN_TOKEN_LENGTH && !INNODB_DEFAULT_STOPWORDS.contains(token)) {
                tokens.add("+" + token + "*");
                if (tokens.size() == MAX_QUERY_TOKENS) {
                    break;
                }
            }
        }
        return String.join(" ", tokens);
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

    private AppBookQuickSearchResult toResult(BookEntity book) {
        BookMetadataEntity metadata = book.getMetadata();
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        return AppBookQuickSearchResult.builder()
                .id(book.getId())
                .title(metadata == null ? null : metadata.getTitle())
                .authors(metadata == null || metadata.getAuthors() == null
                        ? List.of()
                        : metadata.getAuthors().stream().map(AuthorEntity::getName).toList())
                .seriesName(metadata == null ? null : metadata.getSeriesName())
                .seriesNumber(metadata == null ? null : metadata.getSeriesNumber())
                .publishedDate(metadata == null ? null : metadata.getPublishedDate())
                .primaryFileType(primaryFile == null ? null : primaryFile.getBookType().name())
                .primaryFileName(primaryFile == null ? null : primaryFile.getFileName())
                .coverUpdatedOn(metadata == null ? null : metadata.getCoverUpdatedOn())
                .audiobookCoverUpdatedOn(metadata == null ? null : metadata.getAudiobookCoverUpdatedOn())
                .build();
    }
}
