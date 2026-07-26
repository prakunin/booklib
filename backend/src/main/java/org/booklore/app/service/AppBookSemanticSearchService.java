package org.booklore.app.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.booklore.app.dto.AppBookQuickSearchResult;
import org.booklore.config.RecommendationEmbeddingProperties;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.settings.RecommendationEmbeddingSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.repository.BookEmbeddingVectorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.repository.projection.BookEmbeddingCandidate;
import org.booklore.security.policy.ContentRestrictionSpecification;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.recommender.BookVectorService;
import org.booklore.service.recommender.OllamaEmbeddingClient;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalog search over the semantic book embeddings produced by the recommendation pipeline.
 *
 * <p>Complements {@link AppBookQuickSearchService}: the lexical search matches titles, series and
 * authors, this one matches meaning. Both are called independently by the command palette, so every
 * failure here degrades to an empty result instead of an error.
 */
@Slf4j
@Service
public class AppBookSemanticSearchService {

    static final int MAX_RESULTS = 50;
    /**
     * The vector index cannot filter by library, so restricted users over-fetch and filter afterwards.
     */
    private static final int RESTRICTED_OVERFETCH_FACTOR = 4;
    private static final int MAX_CANDIDATES = 500;
    private static final int QUERY_EMBEDDING_CACHE_SIZE = 1_000;
    private static final Duration MODEL_COVERAGE_CACHE_TTL = Duration.ofMinutes(1);

    private final AuthenticationService authenticationService;
    private final BookRepository bookRepository;
    private final UserContentRestrictionRepository restrictionRepository;
    private final BookEmbeddingVectorRepository embeddingVectorRepository;
    private final OllamaEmbeddingClient ollamaEmbeddingClient;
    private final BookVectorService bookVectorService;
    private final AppSettingService appSettingService;
    private final RecommendationEmbeddingProperties embeddingProperties;
    private final TransactionTemplate readOnlyTransaction;
    private final Cache<String, String> queryVectorCache;
    private final Cache<String, Boolean> modelCoverageCache;

    public AppBookSemanticSearchService(AuthenticationService authenticationService,
                                        BookRepository bookRepository,
                                        UserContentRestrictionRepository restrictionRepository,
                                        BookEmbeddingVectorRepository embeddingVectorRepository,
                                        OllamaEmbeddingClient ollamaEmbeddingClient,
                                        BookVectorService bookVectorService,
                                        AppSettingService appSettingService,
                                        RecommendationEmbeddingProperties embeddingProperties,
                                        PlatformTransactionManager transactionManager) {
        this.authenticationService = authenticationService;
        this.bookRepository = bookRepository;
        this.restrictionRepository = restrictionRepository;
        this.embeddingVectorRepository = embeddingVectorRepository;
        this.ollamaEmbeddingClient = ollamaEmbeddingClient;
        this.bookVectorService = bookVectorService;
        this.appSettingService = appSettingService;
        this.embeddingProperties = embeddingProperties;
        // Own instance rather than the shared TransactionTemplate bean: setReadOnly would leak to
        // every other consumer of that bean.
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.queryVectorCache = Caffeine.newBuilder()
                .maximumSize(QUERY_EMBEDDING_CACHE_SIZE)
                .expireAfterWrite(Duration.ofHours(1))
                .build();
        this.modelCoverageCache = Caffeine.newBuilder()
                .maximumSize(8)
                .expireAfterWrite(MODEL_COVERAGE_CACHE_TTL)
                .build();
    }

    public List<AppBookQuickSearchResult> search(String rawQuery, Integer requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return Collections.emptyList();
        }

        // The query is embedded with the model configured right now, so it may only be compared against
        // vectors produced by that same model — not against whatever version the recommendation
        // pipeline last declared "active". Searching partial coverage is intentional: waiting for a
        // complete backfill would take search down on every model or prompt change.
        String modelVersion = ollamaEmbeddingClient.modelVersion();
        if (!hasEmbeddings(modelVersion)) {
            return Collections.emptyList();
        }

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        boolean admin = user.getPermissions().isAdmin();
        Set<Long> accessibleLibraryIds = accessibleLibraryIds(user, admin);
        if (!admin && accessibleLibraryIds.isEmpty()) {
            return Collections.emptyList();
        }

        String queryVectorJson = queryVectorJson(query, modelVersion);
        if (queryVectorJson == null) {
            return Collections.emptyList();
        }

        int limit = Math.clamp(requestedLimit == null ? MAX_RESULTS : requestedLimit, 1, MAX_RESULTS);
        int annLimit = admin ? limit : Math.min(limit * RESTRICTED_OVERFETCH_FACTOR, MAX_CANDIDATES);

        return readOnlyTransaction.execute(status ->
                findVisibleResults(user, admin, accessibleLibraryIds, queryVectorJson, modelVersion, annLimit, limit));
    }

    private List<AppBookQuickSearchResult> findVisibleResults(BookLoreUser user,
                                                              boolean admin,
                                                              Set<Long> accessibleLibraryIds,
                                                              String queryVectorJson,
                                                              String modelVersion,
                                                              int annLimit,
                                                              int limit) {
        double minSimilarity = minSearchSimilarity();

        List<Long> candidateIds = embeddingVectorRepository
                .findNearestByVector(queryVectorJson, annLimit, modelVersion).stream()
                .filter(candidate -> candidate.score() >= minSimilarity)
                .map(BookEmbeddingCandidate::bookId)
                .toList();
        if (candidateIds.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> allowedIds = admin
                ? new HashSet<>(candidateIds)
                : filterVisibleIds(candidateIds, accessibleLibraryIds, restrictionRepository.findByUserId(user.getId()));

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
     * Coverage is checked per request but cached briefly: it flips exactly once per model version,
     * when the backfill writes its first row.
     */
    private boolean hasEmbeddings(String modelVersion) {
        Boolean cached = modelCoverageCache.getIfPresent(modelVersion);
        if (cached != null) {
            return cached;
        }
        boolean present = embeddingVectorRepository.hasEmbeddingsForModel(modelVersion);
        modelCoverageCache.put(modelVersion, present);
        return present;
    }

    /**
     * Returns the query vector as MariaDB vector JSON, or {@code null} when the embedder is unavailable.
     */
    private String queryVectorJson(String query, String modelVersion) {
        String cacheKey = modelVersion + "|" + query;
        String cached = queryVectorCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            double[] vector = ollamaEmbeddingClient.embedQuery(bookVectorService.buildSemanticQueryText(query));
            String vectorJson = bookVectorService.serializeVector(vector);
            queryVectorCache.put(cacheKey, vectorJson);
            return vectorJson;
        } catch (Exception exception) {
            log.warn("Semantic search unavailable, falling back to lexical results only: {}",
                    exception.getMessage());
            return null;
        }
    }

    private double minSearchSimilarity() {
        RecommendationEmbeddingSettings settings =
                appSettingService.getAppSettings().getRecommendationEmbeddingSettings();
        Double configured = settings == null ? null : settings.getMinSearchSimilarity();
        return configured == null ? embeddingProperties.getMinSearchSimilarity() : configured;
    }

    private Set<Long> filterVisibleIds(Collection<Long> candidateIds,
                                       Set<Long> accessibleLibraryIds,
                                       List<UserContentRestrictionEntity> restrictions) {
        Specification<BookEntity> specification = (root, query, cb) -> root.get("id").in(candidateIds);
        specification = specification.and((root, query, cb) ->
                root.get("library").get("id").in(accessibleLibraryIds));
        if (!restrictions.isEmpty()) {
            specification = specification.and(ContentRestrictionSpecification.from(restrictions));
        }
        return bookRepository.findAll(specification).stream()
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
