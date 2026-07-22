package org.booklore.app.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.browse.SortParser;
import org.booklore.browse.SortTerm;
import org.booklore.exception.ApiError;
import org.booklore.app.dto.AppBookDetail;
import org.booklore.app.dto.AppBookProgressResponse;
import org.booklore.app.dto.AppBookSummary;
import org.booklore.app.dto.AppCatalogSummary;
import org.booklore.app.dto.AppLibraryStats;
import org.booklore.app.dto.AppFilterOptions;
import org.booklore.app.dto.AppPageResponse;
import org.booklore.app.dto.UpdateProgressRequest;
import org.booklore.app.dto.BookListRequest;
import org.booklore.app.mapper.AppBookMapper;
import org.booklore.service.browse.BookSpecifications;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.*;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryFacetCountRepository;
import org.booklore.repository.LibraryFacetStateRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserBookFileProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.security.policy.ContentRestrictionSpecification;
import org.booklore.service.browse.BookSortRegistry;
import org.booklore.service.browse.BookSortSpecifications;
import org.booklore.service.inpx.InpxCoverGenerationRequestedEvent;
import org.booklore.service.opds.MagicShelfBookService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Parameter;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.persistence.Query;

@Service
@Transactional(readOnly = true)
public class AppBookService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 50;
    static final int MAX_SELECT_ALL_BOOK_IDS = 500;
    private static final String DEFAULT_SORT = "addedOn";
    private static final int ISBN_QUERY_BATCH_SIZE = 500;
    private static final int MAX_SHELL_IDS_IN_CLAUSE = 1_000;
    private static final String ACCESS_DENIED_TO_LIBRARY_MESSAGE = "Access denied to library ";
    private static final String METADATA_JOIN_CLAUSE = "JOIN b.metadata m";
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_LIBRARY_ID = "libraryId";
    private static final String ATTR_METADATA = "metadata";
    private static final String ATTR_READ_STATUS = "readStatus";
    private static final String ATTR_PERSONAL_RATING = "personalRating";
    private static final String ATTR_ADDED_ON = "addedOn";
    private static final String LIBRARY_ID_CLAUSE = "AND b.library.id = :libraryId";
    private static final String BOOK_FILES_JOIN = "JOIN b.bookFiles bf";
    private static final String BOOK_FORMAT_CLAUSE = "AND bf.isBookFormat = true";
    private static final String COL_C_NAME = "c.name";
    private static final String COL_T_NAME = "t.name";
    private static final Duration LIBRARY_STATS_CACHE_TTL = Duration.ofMinutes(5);

    private final BookRepository bookRepository;
    private final UserBookProgressRepository userBookProgressRepository;
    private final UserBookFileProgressRepository userBookFileProgressRepository;
    private final ShelfRepository shelfRepository;
    private final AuthenticationService authenticationService;
    private final AppBookMapper mobileBookMapper;
    private final AppBookProgressService appBookProgressService;
    private final MagicShelfBookService magicShelfBookService;
    private final EntityManager entityManager;
    private final UserContentRestrictionRepository restrictionRepository;
    private final AppContentRestrictionQueryService restrictionQueryService;
    private final BookSortRegistry bookSortRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final CatalogSummaryCache catalogSummaryCache;
    private final FilterOptionsCache filterOptionsCache;
    private final ShellBookIdsCache shellBookIdsCache;
    private final LibraryFacetCountRepository libraryFacetCountRepository;
    private final LibraryFacetStateRepository libraryFacetStateRepository;
    private final Cache<String, AppLibraryStats> libraryStatsCache = Caffeine.newBuilder()
            .expireAfterWrite(LIBRARY_STATS_CACHE_TTL)
            .maximumSize(10_000)
            .build();

    public AppBookService(BookRepository bookRepository,
                          UserBookProgressRepository userBookProgressRepository,
                          UserBookFileProgressRepository userBookFileProgressRepository,
                          ShelfRepository shelfRepository,
                          AuthenticationService authenticationService,
                          AppBookMapper mobileBookMapper,
                          AppBookProgressService appBookProgressService,
                          MagicShelfBookService magicShelfBookService,
                          EntityManager entityManager,
                          UserContentRestrictionRepository restrictionRepository,
                          AppContentRestrictionQueryService restrictionQueryService,
                          BookSortRegistry bookSortRegistry,
                          ApplicationEventPublisher eventPublisher,
                          CatalogSummaryCache catalogSummaryCache,
                          FilterOptionsCache filterOptionsCache,
                          ShellBookIdsCache shellBookIdsCache,
                          LibraryFacetCountRepository libraryFacetCountRepository,
                          LibraryFacetStateRepository libraryFacetStateRepository) {
        this.bookRepository = bookRepository;
        this.userBookProgressRepository = userBookProgressRepository;
        this.userBookFileProgressRepository = userBookFileProgressRepository;
        this.shelfRepository = shelfRepository;
        this.authenticationService = authenticationService;
        this.mobileBookMapper = mobileBookMapper;
        this.appBookProgressService = appBookProgressService;
        this.magicShelfBookService = magicShelfBookService;
        this.entityManager = entityManager;
        this.restrictionRepository = restrictionRepository;
        this.restrictionQueryService = restrictionQueryService;
        this.bookSortRegistry = bookSortRegistry;
        this.eventPublisher = eventPublisher;
        this.catalogSummaryCache = catalogSummaryCache;
        this.filterOptionsCache = filterOptionsCache;
        this.shellBookIdsCache = shellBookIdsCache;
        this.libraryFacetCountRepository = libraryFacetCountRepository;
        this.libraryFacetStateRepository = libraryFacetStateRepository;
    }

    public AppPageResponse<AppBookSummary> getBooks(BookListRequest req) {

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        int pageNum = req.page() != null && req.page() >= 0 ? req.page() : 0;
        int pageSize = req.size() != null && req.size() > 0 ? Math.min(req.size(), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        // Handle magic shelf: compose the DB-side specification directly (no IN-list)
        if (req.magicShelfId() != null) {
            Pageable pageable = PageRequest.of(pageNum, pageSize);

            Specification<BookEntity> spec = buildSpecification(
                    accessibleLibraryIds, userId, user.getPermissions().isAdmin(), req);
            spec = spec.and(magicShelfBookService.toSpecification(userId, req.magicShelfId()));

            if (Boolean.TRUE.equals(req.unshelved())) {
                spec = spec.and(BookSpecifications.unshelved());
            }
            spec = withSort(spec, req.sort(), req.dir(), userId);

            Page<BookEntity> bookPage = bookRepository.findAll(spec, pageable);
            return buildPageResponse(bookPage, userId);
        }

        Pageable pageable = PageRequest.of(pageNum, pageSize);

        Specification<BookEntity> spec = buildSpecification(
                accessibleLibraryIds, userId, user.getPermissions().isAdmin(), req);

        if (Boolean.TRUE.equals(req.unshelved())) {
            spec = spec.and(BookSpecifications.unshelved());
        }
        spec = withSort(spec, req.sort(), req.dir(), userId);

        Page<BookEntity> bookPage = bookRepository.findAll(spec, pageable);
        return buildPageResponse(bookPage, userId);
    }

    public List<Long> getAllBookIds(BookListRequest req) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        Specification<BookEntity> spec = buildSpecification(
                accessibleLibraryIds, userId, user.getPermissions().isAdmin(), req);

        if (req.magicShelfId() != null) {
            spec = spec.and(magicShelfBookService.toSpecification(userId, req.magicShelfId()));
        }

        if (Boolean.TRUE.equals(req.unshelved())) {
            spec = spec.and(BookSpecifications.unshelved());
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<BookEntity> root = cq.from(BookEntity.class);
        cq.select(root.get("id"))
                .distinct(true)
                .orderBy(cb.asc(root.get("id")));

        if (spec != null) {
            cq.where(spec.toPredicate(root, cq, cb));
        }

        return entityManager.createQuery(cq)
                .setMaxResults(MAX_SELECT_ALL_BOOK_IDS)
                .getResultList();
    }

    public AppCatalogSummary getCatalogSummary() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);
        boolean admin = user.getPermissions().isAdmin();
        // Key encodes the visibility scope so a permission / library-assignment change immediately
        // yields a different key instead of serving an authorization-stale count.
        String key = catalogSummaryKey(user.getId(), admin, accessibleLibraryIds);
        return catalogSummaryCache.get(key, () -> computeCatalogSummary(user, accessibleLibraryIds, admin));
    }

    public AppLibraryStats getLibraryStats(Long libraryId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);
        boolean admin = user.getPermissions().isAdmin();
        String cacheKey = catalogSummaryKey(user.getId(), admin, accessibleLibraryIds)
                + "|library=" + Objects.toString(libraryId, "all");
        return libraryStatsCache.get(cacheKey,
                ignored -> computeLibraryStats(user, accessibleLibraryIds, admin, libraryId));
    }

    private AppLibraryStats computeLibraryStats(
            BookLoreUser user, Set<Long> accessibleLibraryIds, boolean admin, Long libraryId) {
        Specification<BookEntity> visibleBooks = buildAggregateSpecification(
                accessibleLibraryIds, libraryId, user.getId(), admin);
        boolean simpleVisibility = admin || !userHasContentRestrictions(user.getId());
        AppFilterOptions facets = getFilterOptions(libraryId, null, null);

        return new AppLibraryStats(
                bookRepository.count(visibleBooks),
                sumBookFileSize(visibleBooks),
                countDistinctAuthors(visibleBooks, simpleVisibility),
                countDistinctSeries(visibleBooks),
                countDistinctPublishers(visibleBooks),
                averageDaysToFinish(visibleBooks, user.getId()),
                facets,
                countBooksByMonth(visibleBooks, ATTR_ADDED_ON, user.getId(), false),
                countBooksByMonth(visibleBooks, "dateFinished", user.getId(), true),
                aggregateAuthors(visibleBooks, user.getId()),
                aggregateBookFlow(visibleBooks, user.getId()),
                aggregatePublicationRatings(visibleBooks, user.getId()),
                aggregatePageRatings(visibleBooks, user.getId()),
                aggregateRatingTaste(visibleBooks, user.getId()));
    }

    static String catalogSummaryKey(Long userId, boolean admin, Set<Long> accessibleLibraryIds) {
        String libraries = accessibleLibraryIds == null
                ? "*"
                : accessibleLibraryIds.stream().sorted()
                        .map(String::valueOf).collect(Collectors.joining(","));
        return userId + "|" + admin + "|" + libraries;
    }

    private AppCatalogSummary computeCatalogSummary(BookLoreUser user, Set<Long> accessibleLibraryIds, boolean admin) {
        Long userId = user.getId();
        // Aggregate-scoped spec: excludes shell books by id instead of the "(hasFiles OR isPhysical)"
        // OR, so these whole-catalog aggregates can use indexes (see BookSpecifications#notShellBook).
        Specification<BookEntity> visibleBooks = buildAggregateSpecification(accessibleLibraryIds, userId, admin);
        // The author-side fast count is only valid when visibility adds no correlated subqueries.
        // Admins and users without content restrictions have such a spec; both are the heavy cases.
        boolean simpleVisibility = admin || !userHasContentRestrictions(userId);

        long totalBooks = bookRepository.count(visibleBooks);
        long totalAuthors = countDistinctAuthors(visibleBooks, simpleVisibility);
        long totalSeries = countDistinctSeries(visibleBooks);
        long unshelvedBooks = bookRepository.count(visibleBooks.and(BookSpecifications.unshelved()));
        return new AppCatalogSummary(
                totalBooks,
                totalAuthors,
                totalSeries,
                unshelvedBooks,
                countBooksByLibrary(visibleBooks));
    }

    public List<AppBookSummary> getBookSummariesByIds(Set<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return Collections.emptyList();
        }
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Specification<BookEntity> matchingIds = (root, query, cb) -> root.get("id").in(bookIds);
        List<BookEntity> books = bookRepository.findAll(buildBaseSpecification(
                getAccessibleLibraryIds(user), null, userId, user.getPermissions().isAdmin()).and(matchingIds));
        Map<Long, UserBookProgressEntity> progress = getProgressMapForBooks(userId, books);
        return books.stream()
                .map(book -> mobileBookMapper.toSummary(book, progress.get(book.getId())))
                .toList();
    }

    /**
     * Counts distinct authors with at least one visible book. Two shapes: the book-side
     * COUNT(DISTINCT author_id) fans out to one row per (book, author) pair and dedups author_id
     * across the whole mapping table (~1.8s on a 630k-book catalog); the author-side COUNT(*) with
     * an EXISTS avoids both the fan-out and the DISTINCT. The fast path is only correct when the
     * visibility spec touches nothing but the book root - a spec that adds correlated subqueries
     * (content restrictions) cannot be applied inside the EXISTS subquery, so those callers use the
     * book-side query. {@code simpleVisibility} is true for admins and users without restrictions.
     */
    // Package-private for AppBookServiceCountDistinctAuthorsTest, which characterizes that the
    // author-side fast path returns the same count as the book-side query it replaces.
    long countDistinctAuthors(Specification<BookEntity> spec, boolean simpleVisibility) {
        if (!simpleVisibility) {
            return countDistinctAuthorsBookSide(spec);
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<AuthorEntity> author = query.from(AuthorEntity.class);
        Subquery<Long> sub = query.subquery(Long.class);
        Root<BookEntity> book = sub.from(BookEntity.class);
        Join<Object, Object> authors = book.join(ATTR_METADATA).join(F_AUTHORS);
        sub.select(cb.literal(1L)).where(cb.and(
                cb.equal(authors.get("id"), author.get("id")),
                spec.toPredicate(book, query, cb)));
        query.select(cb.count(author.get("id"))).where(cb.exists(sub));
        return entityManager.createQuery(query).getSingleResult();
    }

    private long countDistinctAuthorsBookSide(Specification<BookEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> root = query.from(BookEntity.class);
        query.select(cb.countDistinct(root.join(ATTR_METADATA).join(F_AUTHORS).get("id")));
        query.where(spec.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getSingleResult();
    }

    private long countDistinctSeries(Specification<BookEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> root = query.from(BookEntity.class);
        var seriesName = root.join(ATTR_METADATA).get("seriesName");
        query.select(cb.countDistinct(seriesName));
        query.where(cb.and(
                spec.toPredicate(root, query, cb),
                cb.isNotNull(seriesName),
                cb.notEqual(seriesName, "")));
        return entityManager.createQuery(query).getSingleResult();
    }

    private long countDistinctPublishers(Specification<BookEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> root = query.from(BookEntity.class);
        var publisher = root.join(ATTR_METADATA).get("publisher");
        query.select(cb.countDistinct(publisher));
        query.where(cb.and(
                spec.toPredicate(root, query, cb),
                cb.isNotNull(publisher),
                cb.notEqual(publisher, "")));
        return entityManager.createQuery(query).getSingleResult();
    }

    long sumBookFileSize(Specification<BookEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> root = query.from(BookEntity.class);
        Join<BookEntity, BookFileEntity> files = root.join("bookFiles", jakarta.persistence.criteria.JoinType.LEFT);
        files.on(cb.isTrue(files.get("isBookFormat")));
        query.select(cb.coalesce(cb.sum(files.get("fileSizeKb")), 0L));
        query.where(spec.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getSingleResult();
    }

    List<AppLibraryStats.MonthlyCount> countBooksByMonth(
            Specification<BookEntity> spec, String dateField, Long userId, boolean progressDate) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> root = query.from(BookEntity.class);
        jakarta.persistence.criteria.Expression<Instant> date;
        jakarta.persistence.criteria.Predicate extra = cb.conjunction();

        if (progressDate) {
            Join<BookEntity, UserBookProgressEntity> progress = root.join(
                    "userBookProgress", jakarta.persistence.criteria.JoinType.LEFT);
            progress.on(cb.equal(progress.get("user").get("id"), userId));
            date = progress.get(dateField);
            extra = cb.equal(progress.get(ATTR_READ_STATUS), ReadStatus.READ);
        } else {
            date = root.get(dateField);
        }

        var year = cb.function("YEAR", Integer.class, date);
        var month = cb.function("MONTH", Integer.class, date);
        query.multiselect(year, month, cb.countDistinct(root.get("id")));
        query.where(cb.and(spec.toPredicate(root, query, cb), cb.isNotNull(date), extra));
        query.groupBy(year, month);
        query.orderBy(cb.asc(year), cb.asc(month));
        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new AppLibraryStats.MonthlyCount(
                        row.get(0, Integer.class), row.get(1, Integer.class), row.get(2, Long.class)))
                .toList();
    }

    long averageDaysToFinish(Specification<BookEntity> spec, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Double> query = cb.createQuery(Double.class);
        Root<BookEntity> root = query.from(BookEntity.class);
        Join<BookEntity, UserBookProgressEntity> progress = joinUserProgress(root, cb, userId);
        var finished = progress.<Instant>get("dateFinished");
        var added = root.<Instant>get(ATTR_ADDED_ON);
        var days = cb.function("DATEDIFF", Integer.class, finished, added);
        query.select(cb.avg(days));
        query.where(cb.and(
                spec.toPredicate(root, query, cb),
                cb.equal(progress.get(ATTR_READ_STATUS), ReadStatus.READ),
                cb.isNotNull(finished),
                cb.isNotNull(added)));
        Double result = entityManager.createQuery(query).getSingleResult();
        return result == null ? 0 : Math.max(0, Math.round(result));
    }

    List<AppLibraryStats.AuthorStat> aggregateAuthors(Specification<BookEntity> spec, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> root = query.from(BookEntity.class);
        Join<Object, Object> metadata = root.join(ATTR_METADATA);
        Join<Object, Object> author = metadata.join(F_AUTHORS);
        Join<BookEntity, UserBookProgressEntity> progress = joinUserProgress(root, cb, userId);
        var authorName = author.<String>get("name");
        var bookCount = cb.countDistinct(root.get("id"));
        var totalPages = cb.sumAsLong(metadata.<Integer>get("pageCount"));
        var averageRating = cb.avg(progress.<Integer>get(ATTR_PERSONAL_RATING));
        var readCount = cb.sum(cb.<Long>selectCase()
                .when(cb.equal(progress.get(ATTR_READ_STATUS), ReadStatus.READ), 1L)
                .otherwise(0L));
        query.multiselect(authorName, bookCount, totalPages, averageRating, readCount);
        query.where(cb.and(spec.toPredicate(root, query, cb), cb.isNotNull(authorName)));
        query.groupBy(author.get("id"), authorName);
        query.having(cb.greaterThanOrEqualTo(bookCount, 2L));
        query.orderBy(cb.desc(bookCount), cb.asc(authorName));
        return entityManager.createQuery(query).setMaxResults(50).getResultList().stream()
                .map(row -> new AppLibraryStats.AuthorStat(
                        row.get(0, String.class),
                        row.get(1, Long.class),
                        Optional.ofNullable(row.get(2, Long.class)).orElse(0L),
                        Optional.ofNullable(row.get(3, Double.class)).orElse(0D),
                        Optional.ofNullable(row.get(4, Long.class)).orElse(0L)))
                .toList();
    }

    List<AppLibraryStats.BookFlowCount> aggregateBookFlow(Specification<BookEntity> spec, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> root = query.from(BookEntity.class);
        Join<BookEntity, UserBookProgressEntity> progress = joinUserProgress(root, cb, userId);
        var added = root.<Instant>get(ATTR_ADDED_ON);
        var year = cb.function("YEAR", Integer.class, added);
        var quarter = cb.function("QUARTER", Integer.class, added);
        var status = progress.<ReadStatus>get(ATTR_READ_STATUS);
        var rating = progress.<Integer>get(ATTR_PERSONAL_RATING);
        query.multiselect(year, quarter, status, rating, cb.countDistinct(root.get("id")));
        query.where(cb.and(spec.toPredicate(root, query, cb), cb.isNotNull(added)));
        query.groupBy(year, quarter, status, rating);
        query.orderBy(cb.asc(year), cb.asc(quarter));
        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new AppLibraryStats.BookFlowCount(
                        row.get(0, Integer.class),
                        row.get(1, Integer.class),
                        Optional.ofNullable(row.get(2, ReadStatus.class)).orElse(ReadStatus.UNSET).name(),
                        row.get(3, Integer.class),
                        row.get(4, Long.class)))
                .toList();
    }

    List<AppLibraryStats.PublicationRatingCount> aggregatePublicationRatings(
            Specification<BookEntity> spec, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> root = query.from(BookEntity.class);
        Join<Object, Object> metadata = root.join(ATTR_METADATA);
        Join<BookEntity, UserBookProgressEntity> progress = joinUserProgress(root, cb, userId);
        var publishedDate = metadata.<java.time.LocalDate>get("publishedDate");
        var year = cb.function("YEAR", Integer.class, publishedDate);
        var rating = progress.<Integer>get(ATTR_PERSONAL_RATING);
        query.multiselect(year, rating, cb.countDistinct(root.get("id")));
        query.where(cb.and(
                spec.toPredicate(root, query, cb),
                cb.isNotNull(publishedDate),
                cb.isNotNull(rating),
                cb.greaterThan(rating, 0)));
        query.groupBy(year, rating);
        query.orderBy(cb.asc(year), cb.asc(rating));
        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new AppLibraryStats.PublicationRatingCount(
                        row.get(0, Integer.class), row.get(1, Integer.class), row.get(2, Long.class)))
                .toList();
    }

    List<AppLibraryStats.PageRatingCount> aggregatePageRatings(Specification<BookEntity> spec, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> root = query.from(BookEntity.class);
        Join<Object, Object> metadata = root.join(ATTR_METADATA);
        Join<BookEntity, UserBookProgressEntity> progress = joinUserProgress(root, cb, userId);
        var pages = metadata.<Integer>get("pageCount");
        var rating = progress.<Integer>get(ATTR_PERSONAL_RATING);
        var status = progress.<ReadStatus>get(ATTR_READ_STATUS);
        query.multiselect(pages, rating, status, cb.countDistinct(root.get("id")));
        query.where(cb.and(
                spec.toPredicate(root, query, cb),
                cb.isNotNull(pages),
                cb.greaterThan(pages, 0),
                cb.isNotNull(rating),
                cb.greaterThan(rating, 0)));
        query.groupBy(pages, rating, status);
        query.orderBy(cb.asc(pages), cb.asc(rating));
        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new AppLibraryStats.PageRatingCount(
                        row.get(0, Integer.class),
                        row.get(1, Integer.class),
                        Optional.ofNullable(row.get(2, ReadStatus.class)).orElse(ReadStatus.UNSET).name(),
                        row.get(3, Long.class)))
                .toList();
    }

    List<AppLibraryStats.RatingTasteCount> aggregateRatingTaste(Specification<BookEntity> spec, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> root = query.from(BookEntity.class);
        Join<Object, Object> metadata = root.join(ATTR_METADATA);
        Join<BookEntity, UserBookProgressEntity> progress = joinUserProgress(root, cb, userId);
        var personal = progress.<Integer>get(ATTR_PERSONAL_RATING);
        var metadataRating = metadata.<Double>get("rating");
        var goodreads = metadata.<Double>get("goodreadsRating");
        var amazon = metadata.<Double>get("amazonRating");
        var hardcover = metadata.<Double>get("hardcoverRating");
        var lubimyczytac = metadata.<Double>get("lubimyczytacRating");
        var ranobedb = metadata.<Double>get("ranobedbRating");
        query.multiselect(personal, metadataRating, goodreads, amazon, hardcover, lubimyczytac, ranobedb,
                cb.countDistinct(root.get("id")));
        query.where(cb.and(
                spec.toPredicate(root, query, cb),
                cb.isNotNull(personal),
                cb.greaterThan(personal, 0),
                cb.or(
                        cb.greaterThan(metadataRating, 0D),
                        cb.greaterThan(goodreads, 0D),
                        cb.greaterThan(amazon, 0D),
                        cb.greaterThan(hardcover, 0D),
                        cb.greaterThan(lubimyczytac, 0D),
                        cb.greaterThan(ranobedb, 0D))));
        query.groupBy(personal, metadataRating, goodreads, amazon, hardcover, lubimyczytac, ranobedb);
        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new AppLibraryStats.RatingTasteCount(
                        row.get(0, Integer.class),
                        row.get(1, Double.class),
                        row.get(2, Double.class),
                        row.get(3, Double.class),
                        row.get(4, Double.class),
                        row.get(5, Double.class),
                        row.get(6, Double.class),
                        row.get(7, Long.class)))
                .toList();
    }

    private Join<BookEntity, UserBookProgressEntity> joinUserProgress(
            Root<BookEntity> root, CriteriaBuilder cb, Long userId) {
        Join<BookEntity, UserBookProgressEntity> progress = root.join(
                "userBookProgress", jakarta.persistence.criteria.JoinType.LEFT);
        progress.on(cb.equal(progress.get("user").get("id"), userId));
        return progress;
    }

    private Map<Long, Long> countBooksByLibrary(Specification<BookEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> root = query.from(BookEntity.class);
        var libraryId = root.get("library").get("id");
        query.select(cb.tuple(libraryId, cb.countDistinct(root.get("id"))));
        query.where(spec.toPredicate(root, query, cb));
        query.groupBy(libraryId);
        return entityManager.createQuery(query).getResultList().stream()
                .collect(Collectors.toMap(
                        tuple -> tuple.get(0, Long.class),
                        tuple -> tuple.get(1, Long.class)));
    }

    public Set<String> findExistingIsbns(Long libraryId, Set<String> requestedIsbns) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        validateLibraryAccess(getAccessibleLibraryIds(user), libraryId);

        if (requestedIsbns == null || requestedIsbns.isEmpty()) {
            return Collections.emptySet();
        }

        List<String> normalized = requestedIsbns.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeIsbn)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        Set<String> matches = new HashSet<>();
        for (int offset = 0; offset < normalized.size(); offset += ISBN_QUERY_BATCH_SIZE) {
            List<String> batch = normalized.subList(offset, Math.min(offset + ISBN_QUERY_BATCH_SIZE, normalized.size()));
            List<Tuple> rows = entityManager.createQuery("""
                            SELECT m.book.id, m.isbn13, m.isbn10
                            FROM BookMetadataEntity m
                            WHERE m.book.library.id = :libraryId
                              AND (m.book.deleted IS NULL OR m.book.deleted = false)
                              AND (REPLACE(REPLACE(UPPER(m.isbn13), '-', ''), ' ', '') IN :isbns
                                   OR REPLACE(REPLACE(UPPER(m.isbn10), '-', ''), ' ', '') IN :isbns)
                            """, Tuple.class)
                    .setParameter(PARAM_LIBRARY_ID, libraryId)
                    .setParameter("isbns", batch)
                    .getResultList();
            Set<Long> visibleBookIds = findVisibleBookIds(user, rows.stream()
                    .map(row -> row.get(0, Long.class))
                    .collect(Collectors.toSet()));
            for (Tuple row : rows) {
                if (!visibleBookIds.contains(row.get(0, Long.class))) {
                    continue;
                }
                addNormalizedIsbn(matches, row.get(1, String.class));
                addNormalizedIsbn(matches, row.get(2, String.class));
            }
        }
        matches.retainAll(new HashSet<>(normalized));
        return matches;
    }

    private Set<Long> findVisibleBookIds(BookLoreUser user, Set<Long> bookIds) {
        if (bookIds.isEmpty()) {
            return Collections.emptySet();
        }
        Specification<BookEntity> matchingIds = (root, query, cb) -> root.get("id").in(bookIds);
        Specification<BookEntity> visible = contentRestrictions(
                user.getId(), user.getPermissions().isAdmin()).and(matchingIds);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> root = query.from(BookEntity.class);
        query.select(root.get("id"));
        query.where(visible.toPredicate(root, query, cb));
        return new HashSet<>(entityManager.createQuery(query).getResultList());
    }

    private void addNormalizedIsbn(Set<String> target, String isbn) {
        if (isbn != null && !isbn.isBlank()) {
            target.add(normalizeIsbn(isbn));
        }
    }

    private String normalizeIsbn(String isbn) {
        return isbn.replaceAll("[-\\s]", "").toUpperCase(Locale.ROOT);
    }

    public AppBookDetail getBookDetail(Long bookId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        validateBookAccess(userId, accessibleLibraryIds, book);

        UserBookProgressEntity progress = userBookProgressRepository
                .findByUserIdAndBookId(userId, bookId)
                .orElse(null);

        UserBookFileProgressEntity fileProgress = userBookFileProgressRepository
                .findMostRecentAudiobookProgressByUserIdAndBookId(userId, bookId)
                .orElse(null);

        return mobileBookMapper.toDetail(book, progress, fileProgress);
    }

    @Transactional(readOnly = true)
    public AppBookProgressResponse getBookProgress(Long bookId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        validateBookAccess(userId, accessibleLibraryIds, book);

        UserBookProgressEntity progress = userBookProgressRepository
                .findByUserIdAndBookId(userId, bookId)
                .orElse(null);

        UserBookFileProgressEntity fileProgress = userBookFileProgressRepository
                .findMostRecentAudiobookProgressByUserIdAndBookId(userId, bookId)
                .orElse(null);

        return mobileBookMapper.toProgressResponse(progress, fileProgress);
    }

    public void updateBookProgress(Long bookId, UpdateProgressRequest request) {
        appBookProgressService.updateBookProgress(bookId, request);
    }

    @Transactional(readOnly = true)
    public AppPageResponse<AppBookSummary> searchBooks(
            String query,
            Integer page,
            Integer size) {

        if (query == null || query.trim().isEmpty()) {
            throw ApiError.INVALID_QUERY_PARAMETERS.createException();
        }

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        int pageNum = validatePageNumber(page);
        int pageSize = validatePageSize(size);

        Pageable pageable = PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, DEFAULT_SORT));

        Specification<BookEntity> spec = BookSpecifications.combine(
                BookSpecifications.notDeleted(),
                BookSpecifications.hasDigitalFileOrIsPhysical(),
                BookSpecifications.inLibraries(accessibleLibraryIds),
                BookSpecifications.searchText(query),
                contentRestrictions(userId, user.getPermissions().isAdmin())
        );

        Page<BookEntity> bookPage = bookRepository.findAll(spec, pageable);
        return buildPageResponse(bookPage, userId);
    }

    public List<AppBookSummary> getContinueReading(Integer limit) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        int maxItems = validateLimit(limit, 10);

        List<Long> topIds = userBookProgressRepository.findTopContinueReadingBookIds(
                userId, accessibleLibraryIds, PageRequest.of(0, maxItems));

        if (topIds.isEmpty()) return Collections.emptyList();

        Map<Long, UserBookProgressEntity> progressMap = getProgressMap(userId, new HashSet<>(topIds));

        Map<Long, BookEntity> enrichedMap = findVisibleBooksByIds(user, userId, topIds)
                .stream().collect(Collectors.toMap(BookEntity::getId, b -> b));

        return topIds.stream()
                .filter(enrichedMap::containsKey)
                .map(id -> mobileBookMapper.toSummary(enrichedMap.get(id), progressMap.get(id)))
                .toList();
    }

    public List<AppBookSummary> getContinueListening(Integer limit) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        int maxItems = validateLimit(limit, 10);

        List<Long> topIds = userBookProgressRepository.findTopContinueListeningBookIds(
                userId, accessibleLibraryIds, PageRequest.of(0, maxItems));

        if (topIds.isEmpty()) return Collections.emptyList();

        Map<Long, UserBookProgressEntity> progressMap = getProgressMap(userId, new HashSet<>(topIds));

        Map<Long, BookEntity> enrichedMap = findVisibleBooksByIds(user, userId, topIds)
                .stream().collect(Collectors.toMap(BookEntity::getId, b -> b));

        return topIds.stream()
                .filter(enrichedMap::containsKey)
                .map(id -> mobileBookMapper.toSummary(enrichedMap.get(id), progressMap.get(id)))
                .toList();
    }

    public List<AppBookSummary> getRecentlyAdded(Integer limit) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        int maxItems = validateLimit(limit, 10);

        Specification<BookEntity> spec = BookSpecifications.combine(
                BookSpecifications.notDeleted(),
                BookSpecifications.hasDigitalFileOrIsPhysical(),
                BookSpecifications.inLibraries(accessibleLibraryIds),
                BookSpecifications.addedWithinDays(30),
                contentRestrictions(userId, user.getPermissions().isAdmin())
        );

        Pageable pageable = PageRequest.of(0, maxItems, Sort.by(Sort.Direction.DESC, DEFAULT_SORT));
        Page<BookEntity> bookPage = bookRepository.findAll(spec, pageable);
        Map<Long, UserBookProgressEntity> progressMap = getProgressMapForBooks(userId, bookPage.getContent());

        return bookPage.getContent().stream()
                .map(book -> mobileBookMapper.toSummary(book, progressMap.get(book.getId())))
                .toList();
    }

    public List<AppBookSummary> getRecentlyScanned(Integer limit) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        int maxItems = validateLimit(limit, 10);

        Specification<BookEntity> spec = BookSpecifications.combine(
                BookSpecifications.notDeleted(),
                BookSpecifications.hasScannedOn(),
                BookSpecifications.inLibraries(accessibleLibraryIds),
                contentRestrictions(userId, user.getPermissions().isAdmin())
        );

        Pageable pageable = PageRequest.of(0, maxItems, Sort.by(Sort.Direction.DESC, "scannedOn"));
        Page<BookEntity> bookPage = bookRepository.findAll(spec, pageable);
        Map<Long, UserBookProgressEntity> progressMap = getProgressMapForBooks(userId, bookPage.getContent());

        return bookPage.getContent().stream()
                .map(book -> mobileBookMapper.toSummary(book, progressMap.get(book.getId())))
                .toList();
    }

    public AppPageResponse<AppBookSummary> getRandomBooks(
            Integer page,
            Integer size,
            Long libraryId) {

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        int pageNum = validatePageNumber(page);
        int pageSize = validatePageSize(size);

        Specification<BookEntity> spec = buildBaseSpecification(
                accessibleLibraryIds, libraryId, userId, user.getPermissions().isAdmin());

        long totalElements = bookRepository.count(spec);

        if (totalElements == 0) {
            return AppPageResponse.of(Collections.emptyList(), pageNum, pageSize, 0L);
        }

        long maxOffset = Math.max(0, totalElements - pageSize);
        int randomOffset = ThreadLocalRandom.current().nextInt((int) maxOffset + 1);

        Pageable pageable = PageRequest.of(randomOffset / pageSize, pageSize);
        Page<BookEntity> bookPage = bookRepository.findAll(spec, pageable);

        return buildPageResponse(bookPage, userId);
    }

    public AppPageResponse<AppBookSummary> getBooksByMagicShelf(
            Long magicShelfId,
            Integer page,
            Integer size) {

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();

        int pageNum = validatePageNumber(page);
        int pageSize = validatePageSize(size);

        var booksPage = magicShelfBookService.getBooksByMagicShelfId(userId, magicShelfId, pageNum, pageSize);

        List<Long> orderedBookIds = booksPage.getContent().stream()
                .map(Book::getId)
                .toList();

        if (orderedBookIds.isEmpty()) {
            return AppPageResponse.of(Collections.emptyList(), pageNum, pageSize, 0L);
        }

        Map<Long, BookEntity> bookEntitiesById = bookRepository.findAllById(orderedBookIds).stream()
                .collect(Collectors.toMap(BookEntity::getId, Function.identity()));
        Map<Long, UserBookProgressEntity> progressMap = getProgressMap(userId, bookEntitiesById.keySet());

        List<AppBookSummary> summaries = orderedBookIds.stream()
                .map(bookEntitiesById::get)
                .filter(Objects::nonNull)
                .filter(b -> b.hasFiles() || Boolean.TRUE.equals(b.getIsPhysical()))
                .map(bookEntity -> mobileBookMapper.toSummary(bookEntity, progressMap.get(bookEntity.getId())))
                .toList();

        return AppPageResponse.of(summaries, pageNum, pageSize, booksPage.getTotalElements());
    }

    public AppFilterOptions getFilterOptions(Long libraryId, Long shelfId, Long magicShelfId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        // Validate library access
        if (libraryId != null && accessibleLibraryIds != null && !accessibleLibraryIds.contains(libraryId)) {
            throw ApiError.FORBIDDEN.createException(ACCESS_DENIED_TO_LIBRARY_MESSAGE + libraryId);
        }

        // Validate shelf access
        if (shelfId != null) {
            ShelfEntity shelf = shelfRepository.findById(shelfId)
                    .orElseThrow(() -> ApiError.SHELF_NOT_FOUND.createException(shelfId));
            if (!shelf.isPublic() && !shelf.getUser().getId().equals(userId)) {
                throw ApiError.FORBIDDEN.createException("Access denied to shelf " + shelfId);
            }
        }

        // Access check only. A magic shelf's arbitrary rule Specification cannot be embedded in the
        // JPQL facet aggregates without first materializing every matching id, which is exactly the
        // cost this endpoint avoids on large catalogs. Facets therefore stay library-scoped while
        // the paginated book query applies the shelf rule itself - so on a magic shelf the counts
        // describe the library, not the shelf. Because facets ignore the magic shelf, it is also
        // deliberately absent from the cache key.
        if (magicShelfId != null) {
            assertMagicShelfAccessible(userId, magicShelfId);
        }

        // The key encodes the full visibility scope (user, admin flag, accessible libraries,
        // library/shelf scoping) so a permission or assignment change produces a new key instead of
        // serving stale counts. All access checks above run before the cache so a hit can never
        // bypass them.
        String cacheKey = userId + "|" + user.getPermissions().isAdmin()
                + "|" + (accessibleLibraryIds == null ? "*" : accessibleLibraryIds.stream().sorted()
                        .map(String::valueOf).collect(Collectors.joining(",")))
                + "|" + libraryId + "|" + shelfId;
        return filterOptionsCache.get(cacheKey,
                () -> loadFilterOptions(user, userId, accessibleLibraryIds, libraryId, shelfId));
    }

    /**
     * Serves the filter options from the materialized per-library facet counts when the scope is
     * eligible (unrestricted, no shelf filter) and the target libraries have been materialized;
     * otherwise falls back to the live {@link #computeFilterOptions} aggregation. The materialized
     * path still computes the per-user facets (read status, personal rating) live and merges them in.
     */
    private AppFilterOptions loadFilterOptions(BookLoreUser user, Long userId,
                                               Set<Long> accessibleLibraryIds, Long libraryId, Long shelfId) {
        if (shelfId == null && isUnrestricted(user, userId)) {
            AppFilterOptions materialized = materializedFilterOptionsOrNull(userId, accessibleLibraryIds, libraryId);
            if (materialized != null) {
                return materialized;
            }
        }
        return computeFilterOptions(user, userId, accessibleLibraryIds, libraryId, shelfId);
    }

    private AppFilterOptions computeFilterOptions(BookLoreUser user, Long userId,
                                                  Set<Long> accessibleLibraryIds,
                                                  Long libraryId, Long shelfId) {
        String libraryClause = "";
        String shelfClause = "";
        if (shelfId != null) {
            shelfClause = "AND b.id IN (SELECT sb.id FROM ShelfEntity s JOIN s.bookEntities sb WHERE s.id = :shelfId)";
        }
        if (libraryId != null) {
            libraryClause = LIBRARY_ID_CLAUSE;
        } else if (accessibleLibraryIds != null) {
            libraryClause = "AND b.library.id IN :libraryIds";
        }
        AppContentRestrictionQueryService.RestrictionQueryScope restrictionScope = user.getPermissions().isAdmin()
                ? AppContentRestrictionQueryService.RestrictionQueryScope.empty()
                : restrictionQueryScope(userId);
        Set<Long> shellBookIds = findShellBookIds();
        String scopeClause = buildScopeClause(libraryClause, shelfClause)
                + restrictionScope.clause()
                + visibilityClause(shellBookIds);

        List<AppFilterOptions.CountedOption> authors = queryCountedOptions(
                "a.name", "JOIN b.metadata m JOIN m.authors a", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.LanguageOption> languages = queryCountedOptions(
                "m.language", METADATA_JOIN_CLAUSE,
                "AND m.language IS NOT NULL AND m.language <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId).stream()
                .map(c -> new AppFilterOptions.LanguageOption(
                        c.name(),
                        Locale.forLanguageTag(c.name()).getDisplayLanguage(Locale.ENGLISH),
                        c.count()))
                .toList();

        List<AppFilterOptions.CountedOption> fileTypes = queryCountedOptions(
                "bf.bookType", BOOK_FILES_JOIN, BOOK_FORMAT_CLAUSE,
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> categories = queryCountedOptions(
                COL_C_NAME, "JOIN b.metadata m JOIN m.categories c", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> publishers = queryCountedOptions(
                "m.publisher", METADATA_JOIN_CLAUSE,
                "AND m.publisher IS NOT NULL AND m.publisher <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> seriesOptions = queryCountedOptions(
                "m.seriesName", METADATA_JOIN_CLAUSE,
                "AND m.seriesName IS NOT NULL AND m.seriesName <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> tags = queryCountedOptions(
                COL_T_NAME, "JOIN b.metadata m JOIN m.tags t", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> moods = queryCountedOptions(
                "mo.name", "JOIN b.metadata m JOIN m.moods mo", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> narrators = queryCountedOptions(
                "m.narrator", METADATA_JOIN_CLAUSE,
                "AND m.narrator IS NOT NULL AND m.narrator <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> readStatuses = queryReadStatusCounts(
                userId, scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> ageRatings = queryGroupedCount(
                "CASE " +
                "  WHEN m.ageRating >= 0 AND m.ageRating < 6 THEN '0' " +
                "  WHEN m.ageRating >= 6 AND m.ageRating < 10 THEN '6' " +
                "  WHEN m.ageRating >= 10 AND m.ageRating < 13 THEN '10' " +
                "  WHEN m.ageRating >= 13 AND m.ageRating < 16 THEN '13' " +
                "  WHEN m.ageRating >= 16 AND m.ageRating < 18 THEN '16' " +
                "  WHEN m.ageRating >= 18 AND m.ageRating < 21 THEN '18' " +
                "  WHEN m.ageRating >= 21 THEN '21' " +
                "END",
                METADATA_JOIN_CLAUSE, "AND m.ageRating IS NOT NULL",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> contentRatings = queryCountedOptions(
                "m.contentRating", METADATA_JOIN_CLAUSE,
                "AND m.contentRating IS NOT NULL AND m.contentRating <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> matchScores = queryGroupedCount(
                "CASE " +
                "  WHEN b.metadataMatchScore >= 0.95 THEN '0' " +
                "  WHEN b.metadataMatchScore >= 0.90 THEN '1' " +
                "  WHEN b.metadataMatchScore >= 0.80 THEN '2' " +
                "  WHEN b.metadataMatchScore >= 0.70 THEN '3' " +
                "  WHEN b.metadataMatchScore >= 0.50 THEN '4' " +
                "  WHEN b.metadataMatchScore >= 0.30 THEN '5' " +
                "  WHEN b.metadataMatchScore >= 0.00 THEN '6' " +
                "END",
                "", "AND b.metadataMatchScore IS NOT NULL",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> publishedYears = queryCountedOptions(
                "CAST(YEAR(m.publishedDate) AS string)", METADATA_JOIN_CLAUSE,
                "AND m.publishedDate IS NOT NULL",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> fileSizes = queryGroupedCount(
                "CASE " +
                "  WHEN bf.fileSizeKb < 1024 THEN '0' " +
                "  WHEN bf.fileSizeKb < 10240 THEN '1' " +
                "  WHEN bf.fileSizeKb < 51200 THEN '2' " +
                "  WHEN bf.fileSizeKb < 102400 THEN '3' " +
                "  WHEN bf.fileSizeKb < 512000 THEN '4' " +
                "  WHEN bf.fileSizeKb < 1048576 THEN '5' " +
                "  WHEN bf.fileSizeKb < 2097152 THEN '6' " +
                "  ELSE '7' " +
                "END",
                BOOK_FILES_JOIN, BOOK_FORMAT_CLAUSE,
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        String personalRatingQuery = "SELECT CAST(ubp.personalRating AS string), COUNT(DISTINCT ubp.book.id) " +
                "FROM UserBookProgressEntity ubp " +
                "WHERE ubp.user.id = :userId AND ubp.personalRating IS NOT NULL " +
                "AND ubp.book.id IN (SELECT b.id FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)" +
                scopeClause + ") " +
                "GROUP BY 1 ORDER BY 1 DESC";
        var prQ = entityManager.createQuery(personalRatingQuery, Tuple.class);
        prQ.setParameter(PARAM_USER_ID, userId);
        setFilterQueryParams(prQ, accessibleLibraryIds, libraryId, shelfId);
        List<AppFilterOptions.CountedOption> personalRatings = prQ.getResultList().stream()
                .map(t -> new AppFilterOptions.CountedOption(t.get(0, String.class), t.get(1, Long.class)))
                .toList();

        List<AppFilterOptions.CountedOption> amazonRatings = queryRatingBuckets("m.amazonRating", scopeClause, accessibleLibraryIds, libraryId, shelfId);
        List<AppFilterOptions.CountedOption> goodreadsRatings = queryRatingBuckets("m.goodreadsRating", scopeClause, accessibleLibraryIds, libraryId, shelfId);
        List<AppFilterOptions.CountedOption> hardcoverRatings = queryRatingBuckets("m.hardcoverRating", scopeClause, accessibleLibraryIds, libraryId, shelfId);
        List<AppFilterOptions.CountedOption> lubimyczytacRatings = queryRatingBuckets("m.lubimyczytacRating", scopeClause, accessibleLibraryIds, libraryId, shelfId);
        List<AppFilterOptions.CountedOption> ranobedbRatings = queryRatingBuckets("m.ranobedbRating", scopeClause, accessibleLibraryIds, libraryId, shelfId);
        List<AppFilterOptions.CountedOption> audibleRatings = queryRatingBuckets("m.audibleRating", scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> pageCounts = queryGroupedCount(
                "CASE " +
                "  WHEN m.pageCount < 50 THEN '0' " +
                "  WHEN m.pageCount < 100 THEN '1' " +
                "  WHEN m.pageCount < 200 THEN '2' " +
                "  WHEN m.pageCount < 400 THEN '3' " +
                "  WHEN m.pageCount < 600 THEN '4' " +
                "  WHEN m.pageCount < 1000 THEN '5' " +
                "  ELSE '6' " +
                "END",
                METADATA_JOIN_CLAUSE, "AND m.pageCount IS NOT NULL",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> shelfStatuses = queryGroupedCount(
                "CASE WHEN (SELECT COUNT(s) FROM b.shelves s) > 0 THEN 'shelved' ELSE 'unshelved' END",
                "", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> comicCharacters = queryCountedOptions(
                COL_C_NAME, "JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.characters c", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> comicTeams = queryCountedOptions(
                COL_T_NAME, "JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.teams t", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> comicLocations = queryCountedOptions(
                "l.name", "JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.locations l", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        // Comic Creators — uses creatorMappings join table with role enum
        String creatorJpql = "SELECT cr.name, mapping.role, COUNT(DISTINCT b.id) FROM BookEntity b"
                + " JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.creatorMappings mapping JOIN mapping.creator cr"
                + " WHERE (b.deleted IS NULL OR b.deleted = false)"
                + " " + scopeClause
                + " GROUP BY cr.name, mapping.role ORDER BY COUNT(DISTINCT b.id) DESC";
        var creatorQ = entityManager.createQuery(creatorJpql, Tuple.class);
        setFilterQueryParams(creatorQ, accessibleLibraryIds, libraryId, shelfId);
        creatorQ.setMaxResults(1000);
        List<AppFilterOptions.CountedOption> allCreators = creatorQ.getResultList().stream()
                .map(t -> {
                    String name = t.get(0, String.class);
                    ComicCreatorRole role = t.get(1, ComicCreatorRole.class);
                    long count = t.get(2, Long.class);
                    return new AppFilterOptions.CountedOption(name + ":" + creatorRoleLabel(role), count);
                })
                .toList();

        List<AppFilterOptions.CountedOption> shelves = queryCountedOptions(
                "CAST(s.id AS string) || ':' || s.name", "JOIN b.shelves s", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> libraries = queryCountedOptions(
                "CAST(l.id AS string) || ':' || l.name", "JOIN b.library l", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        return AppFilterOptions.builder()
                .authors(authors)
                .languages(languages)
                .fileTypes(fileTypes)
                .readStatuses(readStatuses)
                .categories(categories)
                .publishers(publishers)
                .series(seriesOptions)
                .tags(tags)
                .moods(moods)
                .narrators(narrators)
                .ageRatings(ageRatings)
                .contentRatings(contentRatings)
                .matchScores(matchScores)
                .publishedYears(publishedYears)
                .fileSizes(fileSizes)
                .personalRatings(personalRatings)
                .amazonRatings(amazonRatings)
                .goodreadsRatings(goodreadsRatings)
                .hardcoverRatings(hardcoverRatings)
                .lubimyczytacRatings(lubimyczytacRatings)
                .ranobedbRatings(ranobedbRatings)
                .audibleRatings(audibleRatings)
                .pageCounts(pageCounts)
                .shelfStatuses(shelfStatuses)
                .comicCharacters(comicCharacters)
                .comicTeams(comicTeams)
                .comicLocations(comicLocations)
                .comicCreators(allCreators)
                .shelves(shelves)
                .libraries(libraries)
                .build();
    }

    // ==== Materialized per-library facet counts ====
    // Facet-type keys stored in library_facet_count. buildGlobalFacets and assembleFilterOptions
    // below must mirror the live computeFilterOptions builder exactly; the parity test
    // AppBookServiceMaterializedFacetsTest fails if the materialized output diverges from live.
    private static final String F_AUTHORS = "authors";
    private static final String F_LANGUAGES = "languages";
    private static final String F_FILE_TYPES = "fileTypes";
    private static final String F_CATEGORIES = "categories";
    private static final String F_PUBLISHERS = "publishers";
    private static final String F_SERIES = "series";
    private static final String F_TAGS = "tags";
    private static final String F_MOODS = "moods";
    private static final String F_NARRATORS = "narrators";
    private static final String F_AGE_RATINGS = "ageRatings";
    private static final String F_CONTENT_RATINGS = "contentRatings";
    private static final String F_MATCH_SCORES = "matchScores";
    private static final String F_PUBLISHED_YEARS = "publishedYears";
    private static final String F_FILE_SIZES = "fileSizes";
    private static final String F_AMAZON_RATINGS = "amazonRatings";
    private static final String F_GOODREADS_RATINGS = "goodreadsRatings";
    private static final String F_HARDCOVER_RATINGS = "hardcoverRatings";
    private static final String F_LUBIMYCZYTAC_RATINGS = "lubimyczytacRatings";
    private static final String F_RANOBEDB_RATINGS = "ranobedbRatings";
    private static final String F_AUDIBLE_RATINGS = "audibleRatings";
    private static final String F_PAGE_COUNTS = "pageCounts";
    private static final String F_SHELF_STATUSES = "shelfStatuses";
    private static final String F_COMIC_CHARACTERS = "comicCharacters";
    private static final String F_COMIC_TEAMS = "comicTeams";
    private static final String F_COMIC_LOCATIONS = "comicLocations";
    private static final String F_COMIC_CREATORS = "comicCreators";
    private static final String F_SHELVES = "shelves";
    private static final String F_LIBRARIES = "libraries";

    private static final List<String> GLOBAL_FACET_TYPES = List.of(
            F_AUTHORS, F_LANGUAGES, F_FILE_TYPES, F_CATEGORIES, F_PUBLISHERS, F_SERIES, F_TAGS, F_MOODS,
            F_NARRATORS, F_AGE_RATINGS, F_CONTENT_RATINGS, F_MATCH_SCORES, F_PUBLISHED_YEARS, F_FILE_SIZES,
            F_AMAZON_RATINGS, F_GOODREADS_RATINGS, F_HARDCOVER_RATINGS, F_LUBIMYCZYTAC_RATINGS,
            F_RANOBEDB_RATINGS, F_AUDIBLE_RATINGS, F_PAGE_COUNTS, F_SHELF_STATUSES, F_COMIC_CHARACTERS,
            F_COMIC_TEAMS, F_COMIC_LOCATIONS, F_COMIC_CREATORS, F_SHELVES, F_LIBRARIES);

    private static final int FACET_LIMIT = 1000;
    private static final int FACET_VALUE_MAX = 512;
    private static final int FACET_STALE_HOURS = 24;

    /**
     * The GLOBAL facets (derived only from book/metadata/file tables, no user dimension), keyed by
     * facet type. Languages are stored as raw codes; assembleFilterOptions derives the display label.
     * Shared by the live path and the materialized recompute so both produce identical values.
     * User-scoped facets (read status, personal rating) are computed separately.
     */
    private Map<String, List<AppFilterOptions.CountedOption>> buildGlobalFacets(
            String scopeClause, Set<Long> accessibleLibraryIds, Long libraryId, Long shelfId) {
        Map<String, List<AppFilterOptions.CountedOption>> f = new LinkedHashMap<>();
        f.put(F_AUTHORS, queryCountedOptions("a.name", "JOIN b.metadata m JOIN m.authors a", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_LANGUAGES, queryCountedOptions("m.language", METADATA_JOIN_CLAUSE,
                "AND m.language IS NOT NULL AND m.language <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_FILE_TYPES, queryCountedOptions("bf.bookType", BOOK_FILES_JOIN,
                BOOK_FORMAT_CLAUSE, scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_CATEGORIES, queryCountedOptions(COL_C_NAME, "JOIN b.metadata m JOIN m.categories c", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_PUBLISHERS, queryCountedOptions("m.publisher", METADATA_JOIN_CLAUSE,
                "AND m.publisher IS NOT NULL AND m.publisher <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_SERIES, queryCountedOptions("m.seriesName", METADATA_JOIN_CLAUSE,
                "AND m.seriesName IS NOT NULL AND m.seriesName <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_TAGS, queryCountedOptions(COL_T_NAME, "JOIN b.metadata m JOIN m.tags t", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_MOODS, queryCountedOptions("mo.name", "JOIN b.metadata m JOIN m.moods mo", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_NARRATORS, queryCountedOptions("m.narrator", METADATA_JOIN_CLAUSE,
                "AND m.narrator IS NOT NULL AND m.narrator <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_AGE_RATINGS, queryGroupedCount(
                "CASE " +
                "  WHEN m.ageRating >= 0 AND m.ageRating < 6 THEN '0' " +
                "  WHEN m.ageRating >= 6 AND m.ageRating < 10 THEN '6' " +
                "  WHEN m.ageRating >= 10 AND m.ageRating < 13 THEN '10' " +
                "  WHEN m.ageRating >= 13 AND m.ageRating < 16 THEN '13' " +
                "  WHEN m.ageRating >= 16 AND m.ageRating < 18 THEN '16' " +
                "  WHEN m.ageRating >= 18 AND m.ageRating < 21 THEN '18' " +
                "  WHEN m.ageRating >= 21 THEN '21' " +
                "END",
                METADATA_JOIN_CLAUSE, "AND m.ageRating IS NOT NULL",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_CONTENT_RATINGS, queryCountedOptions("m.contentRating", METADATA_JOIN_CLAUSE,
                "AND m.contentRating IS NOT NULL AND m.contentRating <> ''",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_MATCH_SCORES, queryGroupedCount(
                "CASE " +
                "  WHEN b.metadataMatchScore >= 0.95 THEN '0' " +
                "  WHEN b.metadataMatchScore >= 0.90 THEN '1' " +
                "  WHEN b.metadataMatchScore >= 0.80 THEN '2' " +
                "  WHEN b.metadataMatchScore >= 0.70 THEN '3' " +
                "  WHEN b.metadataMatchScore >= 0.50 THEN '4' " +
                "  WHEN b.metadataMatchScore >= 0.30 THEN '5' " +
                "  WHEN b.metadataMatchScore >= 0.00 THEN '6' " +
                "END",
                "", "AND b.metadataMatchScore IS NOT NULL",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_PUBLISHED_YEARS, queryCountedOptions("CAST(YEAR(m.publishedDate) AS string)",
                METADATA_JOIN_CLAUSE, "AND m.publishedDate IS NOT NULL",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_FILE_SIZES, queryGroupedCount(
                "CASE " +
                "  WHEN bf.fileSizeKb < 1024 THEN '0' " +
                "  WHEN bf.fileSizeKb < 10240 THEN '1' " +
                "  WHEN bf.fileSizeKb < 51200 THEN '2' " +
                "  WHEN bf.fileSizeKb < 102400 THEN '3' " +
                "  WHEN bf.fileSizeKb < 512000 THEN '4' " +
                "  WHEN bf.fileSizeKb < 1048576 THEN '5' " +
                "  WHEN bf.fileSizeKb < 2097152 THEN '6' " +
                "  ELSE '7' " +
                "END",
                BOOK_FILES_JOIN, BOOK_FORMAT_CLAUSE,
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_AMAZON_RATINGS, queryRatingBuckets("m.amazonRating", scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_GOODREADS_RATINGS, queryRatingBuckets("m.goodreadsRating", scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_HARDCOVER_RATINGS, queryRatingBuckets("m.hardcoverRating", scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_LUBIMYCZYTAC_RATINGS, queryRatingBuckets("m.lubimyczytacRating", scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_RANOBEDB_RATINGS, queryRatingBuckets("m.ranobedbRating", scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_AUDIBLE_RATINGS, queryRatingBuckets("m.audibleRating", scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_PAGE_COUNTS, queryGroupedCount(
                "CASE " +
                "  WHEN m.pageCount < 50 THEN '0' " +
                "  WHEN m.pageCount < 100 THEN '1' " +
                "  WHEN m.pageCount < 200 THEN '2' " +
                "  WHEN m.pageCount < 400 THEN '3' " +
                "  WHEN m.pageCount < 600 THEN '4' " +
                "  WHEN m.pageCount < 1000 THEN '5' " +
                "  ELSE '6' " +
                "END",
                METADATA_JOIN_CLAUSE, "AND m.pageCount IS NOT NULL",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_SHELF_STATUSES, queryGroupedCount(
                "CASE WHEN (SELECT COUNT(s) FROM b.shelves s) > 0 THEN 'shelved' ELSE 'unshelved' END",
                "", "", scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_COMIC_CHARACTERS, queryCountedOptions(COL_C_NAME,
                "JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.characters c", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_COMIC_TEAMS, queryCountedOptions(COL_T_NAME,
                "JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.teams t", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_COMIC_LOCATIONS, queryCountedOptions("l.name",
                "JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.locations l", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_COMIC_CREATORS, queryComicCreators(scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_SHELVES, queryCountedOptions("CAST(s.id AS string) || ':' || s.name", "JOIN b.shelves s", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        f.put(F_LIBRARIES, queryCountedOptions("CAST(l.id AS string) || ':' || l.name", "JOIN b.library l", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId));
        return f;
    }

    // Safe: the JPQL is assembled from internally-defined clause fragments only; every user-supplied
    // value (library/shelf ids) is bound as a named parameter via setFilterQueryParams, never concatenated.
    @SuppressWarnings("java:S2077")
    private List<AppFilterOptions.CountedOption> queryComicCreators(
            String scopeClause, Set<Long> accessibleLibraryIds, Long libraryId, Long shelfId) {
        String creatorJpql = "SELECT cr.name, mapping.role, COUNT(DISTINCT b.id) FROM BookEntity b"
                + " JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.creatorMappings mapping JOIN mapping.creator cr"
                + " WHERE (b.deleted IS NULL OR b.deleted = false)"
                + " " + scopeClause
                + " GROUP BY cr.name, mapping.role ORDER BY COUNT(DISTINCT b.id) DESC";
        var creatorQ = entityManager.createQuery(creatorJpql, Tuple.class);
        setFilterQueryParams(creatorQ, accessibleLibraryIds, libraryId, shelfId);
        creatorQ.setMaxResults(FACET_LIMIT);
        return creatorQ.getResultList().stream()
                .map(t -> new AppFilterOptions.CountedOption(
                        t.get(0, String.class) + ":" + creatorRoleLabel(t.get(1, ComicCreatorRole.class)),
                        t.get(2, Long.class)))
                .toList();
    }

    // Safe: the JPQL is assembled from internally-defined clause fragments only; every user-supplied
    // value (user/library/shelf ids) is bound as a named parameter, never concatenated.
    @SuppressWarnings("java:S2077")
    private List<AppFilterOptions.CountedOption> queryPersonalRatingCounts(
            Long userId, String scopeClause, Set<Long> accessibleLibraryIds, Long libraryId, Long shelfId) {
        String jpql = "SELECT CAST(ubp.personalRating AS string), COUNT(DISTINCT ubp.book.id) "
                + "FROM UserBookProgressEntity ubp "
                + "WHERE ubp.user.id = :userId AND ubp.personalRating IS NOT NULL "
                + "AND ubp.book.id IN (SELECT b.id FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)"
                + scopeClause + ") "
                + "GROUP BY 1 ORDER BY 1 DESC";
        var q = entityManager.createQuery(jpql, Tuple.class);
        q.setParameter(PARAM_USER_ID, userId);
        setFilterQueryParams(q, accessibleLibraryIds, libraryId, shelfId);
        return q.getResultList().stream()
                .map(t -> new AppFilterOptions.CountedOption(t.get(0, String.class), t.get(1, Long.class)))
                .toList();
    }

    private AppFilterOptions assembleFilterOptions(
            Map<String, List<AppFilterOptions.CountedOption>> g,
            List<AppFilterOptions.CountedOption> readStatuses,
            List<AppFilterOptions.CountedOption> personalRatings) {
        return AppFilterOptions.builder()
                .authors(facet(g, F_AUTHORS))
                .languages(facet(g, F_LANGUAGES).stream()
                        .map(c -> new AppFilterOptions.LanguageOption(
                                c.name(),
                                Locale.forLanguageTag(c.name()).getDisplayLanguage(Locale.ENGLISH),
                                c.count()))
                        .toList())
                .fileTypes(facet(g, F_FILE_TYPES))
                .readStatuses(readStatuses)
                .categories(facet(g, F_CATEGORIES))
                .publishers(facet(g, F_PUBLISHERS))
                .series(facet(g, F_SERIES))
                .tags(facet(g, F_TAGS))
                .moods(facet(g, F_MOODS))
                .narrators(facet(g, F_NARRATORS))
                .ageRatings(facet(g, F_AGE_RATINGS))
                .contentRatings(facet(g, F_CONTENT_RATINGS))
                .matchScores(facet(g, F_MATCH_SCORES))
                .publishedYears(facet(g, F_PUBLISHED_YEARS))
                .fileSizes(facet(g, F_FILE_SIZES))
                .personalRatings(personalRatings)
                .amazonRatings(facet(g, F_AMAZON_RATINGS))
                .goodreadsRatings(facet(g, F_GOODREADS_RATINGS))
                .hardcoverRatings(facet(g, F_HARDCOVER_RATINGS))
                .lubimyczytacRatings(facet(g, F_LUBIMYCZYTAC_RATINGS))
                .ranobedbRatings(facet(g, F_RANOBEDB_RATINGS))
                .audibleRatings(facet(g, F_AUDIBLE_RATINGS))
                .pageCounts(facet(g, F_PAGE_COUNTS))
                .shelfStatuses(facet(g, F_SHELF_STATUSES))
                .comicCharacters(facet(g, F_COMIC_CHARACTERS))
                .comicTeams(facet(g, F_COMIC_TEAMS))
                .comicLocations(facet(g, F_COMIC_LOCATIONS))
                .comicCreators(facet(g, F_COMIC_CREATORS))
                .shelves(facet(g, F_SHELVES))
                .libraries(facet(g, F_LIBRARIES))
                .build();
    }

    private static List<AppFilterOptions.CountedOption> facet(
            Map<String, List<AppFilterOptions.CountedOption>> g, String key) {
        return g.getOrDefault(key, List.of());
    }

    private boolean isUnrestricted(BookLoreUser user, Long userId) {
        return user.getPermissions().isAdmin() || restrictionQueryScope(userId).clause().isEmpty();
    }

    private AppFilterOptions materializedFilterOptionsOrNull(Long userId,
                                                             Set<Long> accessibleLibraryIds, Long libraryId) {
        List<Long> targetLibraryIds = resolveTargetLibraryIds(accessibleLibraryIds, libraryId);
        if (targetLibraryIds.isEmpty()) {
            return null;
        }
        Set<Long> computed = libraryFacetStateRepository.findAll().stream()
                .map(LibraryFacetStateEntity::getLibraryId).collect(Collectors.toSet());
        if (!computed.containsAll(targetLibraryIds)) {
            return null;
        }
        Map<String, List<AppFilterOptions.CountedOption>> global = toFacetMap(
                libraryFacetCountRepository.sumByLibraryIds(targetLibraryIds));

        String scopeClause = unrestrictedFacetScope(accessibleLibraryIds, libraryId);
        List<AppFilterOptions.CountedOption> readStatuses = queryReadStatusCounts(
                userId, scopeClause, accessibleLibraryIds, libraryId, null);
        List<AppFilterOptions.CountedOption> personalRatings = queryPersonalRatingCounts(
                userId, scopeClause, accessibleLibraryIds, libraryId, null);
        return assembleFilterOptions(global, readStatuses, personalRatings);
    }

    private List<Long> resolveTargetLibraryIds(Set<Long> accessibleLibraryIds, Long libraryId) {
        if (libraryId != null) {
            return List.of(libraryId);
        }
        if (accessibleLibraryIds != null) {
            return List.copyOf(accessibleLibraryIds);
        }
        return allLibraryIds();
    }

    private List<Long> allLibraryIds() {
        return entityManager.createQuery("SELECT DISTINCT b.library.id FROM BookEntity b", Long.class)
                .getResultList();
    }

    private String unrestrictedFacetScope(Set<Long> accessibleLibraryIds, Long libraryId) {
        String libraryClause = "";
        if (libraryId != null) {
            libraryClause = LIBRARY_ID_CLAUSE;
        } else if (accessibleLibraryIds != null) {
            libraryClause = "AND b.library.id IN :libraryIds";
        }
        return buildScopeClause(libraryClause, "") + visibilityClause(findShellBookIds());
    }

    private Map<String, List<AppFilterOptions.CountedOption>> toFacetMap(
            List<LibraryFacetCountRepository.FacetCountSum> rows) {
        Map<String, List<AppFilterOptions.CountedOption>> byType = new HashMap<>();
        for (LibraryFacetCountRepository.FacetCountSum r : rows) {
            byType.computeIfAbsent(r.getFacetType(), k -> new ArrayList<>())
                    .add(new AppFilterOptions.CountedOption(r.getFacetValue(), r.getBookCount()));
        }
        Map<String, List<AppFilterOptions.CountedOption>> result = new LinkedHashMap<>();
        for (String type : GLOBAL_FACET_TYPES) {
            List<AppFilterOptions.CountedOption> options = byType.getOrDefault(type, new ArrayList<>());
            options.sort(Comparator.comparingLong(AppFilterOptions.CountedOption::count).reversed());
            result.put(type, options.size() > FACET_LIMIT
                    ? List.copyOf(options.subList(0, FACET_LIMIT))
                    : List.copyOf(options));
        }
        return result;
    }

    /**
     * Recomputes the materialized facet rows for one library from the current book data, unrestricted
     * and shell-excluded (matching the live facet visibility). Delete-then-insert replaces the
     * library's rows atomically within the transaction. Called per library by the recompute task so
     * each library commits independently.
     */
    @Transactional
    public void recomputeLibraryFacetCounts(Long libraryId) {
        String scopeClause = LIBRARY_ID_CLAUSE + visibilityClause(findShellBookIds());
        Map<String, List<AppFilterOptions.CountedOption>> global =
                buildGlobalFacets(scopeClause, null, libraryId, null);
        libraryFacetCountRepository.deleteByLibraryId(libraryId);
        List<LibraryFacetCountEntity> rows = new ArrayList<>();
        global.forEach((type, options) -> {
            for (AppFilterOptions.CountedOption o : options) {
                if (o.name() == null) {
                    continue;
                }
                String value = o.name().length() > FACET_VALUE_MAX ? o.name().substring(0, FACET_VALUE_MAX) : o.name();
                rows.add(LibraryFacetCountEntity.builder()
                        .libraryId(libraryId).facetType(type).facetValue(value).bookCount(o.count()).build());
            }
        });
        libraryFacetCountRepository.saveAll(rows);
        libraryFacetStateRepository.save(LibraryFacetStateEntity.builder()
                .libraryId(libraryId).computedAt(Instant.now()).build());
    }

    /**
     * Libraries whose materialized facet counts are missing, older than {@value #FACET_STALE_HOURS}h
     * (a safety net for changes the timestamp check can't see, e.g. hard deletes), or stale relative
     * to the newest book change in the library.
     */
    public List<Long> findDirtyLibraryIds() {
        List<Long> libraryIds = allLibraryIds();
        Map<Long, Instant> computedAt = libraryFacetStateRepository.findAll().stream()
                .collect(Collectors.toMap(LibraryFacetStateEntity::getLibraryId,
                        LibraryFacetStateEntity::getComputedAt));
        Instant staleBefore = Instant.now().minus(FACET_STALE_HOURS, ChronoUnit.HOURS);
        return libraryIds.stream()
                .filter(id -> {
                    Instant computed = computedAt.get(id);
                    if (computed == null || computed.isBefore(staleBefore)) {
                        return true;
                    }
                    Instant maxChange = maxBookChange(id);
                    return maxChange != null && maxChange.isAfter(computed);
                })
                .toList();
    }

    private Instant maxBookChange(Long libraryId) {
        Tuple t = entityManager.createQuery(
                        "SELECT MAX(b.addedOn), MAX(b.scannedOn), MAX(b.metadataUpdatedAt), MAX(b.deletedAt) "
                                + "FROM BookEntity b WHERE b.library.id = :libraryId", Tuple.class)
                .setParameter(PARAM_LIBRARY_ID, libraryId)
                .getSingleResult();
        return Stream.of(t.get(0, Instant.class), t.get(1, Instant.class),
                        t.get(2, Instant.class), t.get(3, Instant.class))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    public void updateReadStatus(Long bookId, ReadStatus status) {
        appBookProgressService.updateReadStatus(bookId, status);
    }

    public void updatePersonalRating(Long bookId, Integer rating) {
        appBookProgressService.updatePersonalRating(bookId, rating);
    }

    private void validateLibraryAccess(Set<Long> accessibleLibraryIds, Long libraryId) {
        if (accessibleLibraryIds != null && !accessibleLibraryIds.contains(libraryId)) {
            throw ApiError.FORBIDDEN.createException("Access denied to this book");
        }
    }

    private void validateBookAccess(Long userId, Set<Long> accessibleLibraryIds, BookEntity book) {
        validateLibraryAccess(accessibleLibraryIds, book.getLibrary().getId());
        Specification<BookEntity> sameBook = (root, query, cb) -> cb.equal(root.get("id"), book.getId());
        if (!bookRepository.exists(contentRestrictions(
                userId, accessibleLibraryIds == null).and(sameBook))) {
            throw ApiError.FORBIDDEN.createException("Access denied to this book");
        }
    }

    @SuppressWarnings("java:S1168") // three-state contract: null = admin/unrestricted (no library filter applied), empty = restricted to nothing, non-empty = specific IDs; callers branch on != null, so Set.of() would wrongly restrict admins to zero libraries
    private Set<Long> getAccessibleLibraryIds(BookLoreUser user) {
        if (user.getPermissions().isAdmin()) {
            return null;
        }
        if (user.getAssignedLibraries() == null || user.getAssignedLibraries().isEmpty()) {
            return Collections.emptySet();
        }
        return user.getAssignedLibraries().stream()
                .map(Library::getId)
                .collect(Collectors.toSet());
    }

    private Map<Long, UserBookProgressEntity> getProgressMap(Long userId, Set<Long> bookIds) {
        if (bookIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userBookProgressRepository.findByUserIdAndBookIdIn(userId, bookIds).stream()
                .collect(Collectors.toMap(
                        p -> p.getBook().getId(),
                        Function.identity()
                ));
    }

    private List<BookEntity> findVisibleBooksByIds(BookLoreUser user, Long userId, Collection<Long> bookIds) {
        if (bookIds.isEmpty()) {
            return Collections.emptyList();
        }
        Specification<BookEntity> matchingIds = (root, query, cb) -> root.get("id").in(bookIds);
        return bookRepository.findAll(buildBaseSpecification(
                getAccessibleLibraryIds(user), null, userId, user.getPermissions().isAdmin()).and(matchingIds));
    }

    private Specification<BookEntity> buildSpecification(
            Set<Long> accessibleLibraryIds,
            Long userId,
            boolean isAdmin,
            BookListRequest req) {

        List<Specification<BookEntity>> specs = new ArrayList<>();
        specs.add(BookSpecifications.notDeleted());
        specs.add(BookSpecifications.hasDigitalFileOrIsPhysical());
        specs.add(contentRestrictions(userId, isAdmin));

        addLibraryScope(specs, accessibleLibraryIds, req);
        addShelfScope(specs, req, userId);

        if (req.search() != null && !req.search().trim().isEmpty()) {
            specs.add(BookSpecifications.searchText(req.search()));
        }

        int filterStartIndex = specs.size();

        addFilterSpecs(specs, req, userId);

        return combineSpecs(specs, filterStartIndex, req);
    }

    private void addLibraryScope(List<Specification<BookEntity>> specs, Set<Long> accessibleLibraryIds, BookListRequest req) {
        if (accessibleLibraryIds != null) {
            if (req.libraryId() != null && accessibleLibraryIds.contains(req.libraryId())) {
                specs.add(BookSpecifications.inLibrary(req.libraryId()));
            } else if (req.libraryId() != null) {
                throw ApiError.FORBIDDEN.createException(ACCESS_DENIED_TO_LIBRARY_MESSAGE + req.libraryId());
            } else {
                specs.add(BookSpecifications.inLibraries(accessibleLibraryIds));
            }
        } else if (req.libraryId() != null) {
            specs.add(BookSpecifications.inLibrary(req.libraryId()));
        }
    }

    private void addShelfScope(List<Specification<BookEntity>> specs, BookListRequest req, Long userId) {
        if (req.shelfId() == null) {
            return;
        }
        ShelfEntity shelf = shelfRepository.findById(req.shelfId())
                .orElseThrow(() -> ApiError.SHELF_NOT_FOUND.createException(req.shelfId()));
        if (!shelf.isPublic() && !shelf.getUser().getId().equals(userId)) {
            throw ApiError.FORBIDDEN.createException("Access denied to shelf " + req.shelfId());
        }
        specs.add(BookSpecifications.inShelf(req.shelfId()));
    }

    private void addFilterSpecs(List<Specification<BookEntity>> specs, BookListRequest req, Long userId) {
        String mode = req.effectiveFilterMode();
        addMultiValueFilter(specs, req.status(), cleaned -> BookSpecifications.withReadStatuses(cleaned, userId, mode));
        addMultiValueFilter(specs, req.fileType(), cleaned -> BookSpecifications.withFileTypes(cleaned, mode));

        if (req.minRating() != null) {
            specs.add(BookSpecifications.withMinRating(req.minRating(), userId));
        }
        if (req.maxRating() != null) {
            specs.add(BookSpecifications.withMaxRating(req.maxRating(), userId));
        }

        addMultiValueFilter(specs, req.authors(), cleaned -> BookSpecifications.withAuthors(cleaned, mode));
        addMultiValueFilter(specs, req.language(), cleaned -> BookSpecifications.withLanguages(cleaned, mode));
        addMultiValueFilter(specs, req.series(), cleaned -> BookSpecifications.inSeriesMulti(cleaned, mode));
        addMultiValueFilter(specs, req.category(), cleaned -> BookSpecifications.withCategories(cleaned, mode));
        addMultiValueFilter(specs, req.publisher(), cleaned -> BookSpecifications.withPublishers(cleaned, mode));
        addMultiValueFilter(specs, req.tag(), cleaned -> BookSpecifications.withTags(cleaned, mode));
        addMultiValueFilter(specs, req.mood(), cleaned -> BookSpecifications.withMoods(cleaned, mode));
        addMultiValueFilter(specs, req.narrator(), cleaned -> BookSpecifications.withNarrators(cleaned, mode));
        addMultiValueFilter(specs, req.ageRating(), cleaned -> BookSpecifications.withAgeRatings(cleaned, mode));
        addMultiValueFilter(specs, req.contentRating(), cleaned -> BookSpecifications.withContentRatings(cleaned, mode));
        addMultiValueFilter(specs, req.matchScore(), cleaned -> BookSpecifications.withMatchScores(cleaned, mode));
        addMultiValueFilter(specs, req.publishedDate(), cleaned -> BookSpecifications.withPublishedYears(cleaned, mode));
        addMultiValueFilter(specs, req.fileSize(), cleaned -> BookSpecifications.withFileSizes(cleaned, mode));
        addMultiValueFilter(specs, req.personalRating(), cleaned -> BookSpecifications.withPersonalRatings(cleaned, userId, mode));
        addMultiValueFilter(specs, req.amazonRating(), cleaned -> BookSpecifications.withAmazonRatings(cleaned, mode));
        addMultiValueFilter(specs, req.goodreadsRating(), cleaned -> BookSpecifications.withGoodreadsRatings(cleaned, mode));
        addMultiValueFilter(specs, req.hardcoverRating(), cleaned -> BookSpecifications.withHardcoverRatings(cleaned, mode));
        addMultiValueFilter(specs, req.lubimyczytacRating(), cleaned -> BookSpecifications.withLubimyczytacRatings(cleaned, mode));
        addMultiValueFilter(specs, req.ranobedbRating(), cleaned -> BookSpecifications.withRanobedbRatings(cleaned, mode));
        addMultiValueFilter(specs, req.audibleRating(), cleaned -> BookSpecifications.withAudibleRatings(cleaned, mode));
        addMultiValueFilter(specs, req.pageCount(), cleaned -> BookSpecifications.withPageCounts(cleaned, mode));
        addMultiValueFilter(specs, req.shelfStatus(), cleaned -> BookSpecifications.withShelfStatus(cleaned, mode));
        addMultiValueFilter(specs, req.comicCharacter(), cleaned -> BookSpecifications.withComicCharacters(cleaned, mode));
        addMultiValueFilter(specs, req.comicTeam(), cleaned -> BookSpecifications.withComicTeams(cleaned, mode));
        addMultiValueFilter(specs, req.comicLocation(), cleaned -> BookSpecifications.withComicLocations(cleaned, mode));
        addMultiValueFilter(specs, req.comicCreator(), cleaned -> BookSpecifications.withComicCreators(cleaned, mode));
        addMultiValueFilter(specs, req.shelves(), cleaned -> BookSpecifications.inShelves(cleaned, mode));
        addMultiValueFilter(specs, req.libraries(), cleaned -> BookSpecifications.inLibraries(cleaned, mode));
    }

    private void addMultiValueFilter(List<Specification<BookEntity>> specs, List<String> rawValues,
                                     Function<List<String>, Specification<BookEntity>> specFactory) {
        if (rawValues == null || rawValues.isEmpty()) {
            return;
        }
        List<String> cleaned = BookListRequest.cleanValues(rawValues);
        if (!cleaned.isEmpty()) {
            specs.add(specFactory.apply(cleaned));
        }
    }

    private Specification<BookEntity> combineSpecs(List<Specification<BookEntity>> specs, int filterStartIndex, BookListRequest req) {
        List<Specification<BookEntity>> baseSpecs = new ArrayList<>(specs.subList(0, filterStartIndex));
        List<Specification<BookEntity>> filterSpecs = specs.subList(filterStartIndex, specs.size());

        Specification<BookEntity> combinedFilters = null;
        if (!filterSpecs.isEmpty()) {
            if ("or".equals(req.effectiveFilterMode())) {
                combinedFilters = filterSpecs.stream()
                        .reduce(Specification::or)
                        .orElse(null);
            } else {
                combinedFilters = BookSpecifications.combine(filterSpecs.toArray(Specification[]::new));
            }
        }

        Specification<BookEntity> result = BookSpecifications.combine(baseSpecs.toArray(Specification[]::new));
        return combinedFilters == null ? result : result.and(combinedFilters);
    }

    private Specification<BookEntity> withSort(
            Specification<BookEntity> filter,
            String sort,
            String direction,
            Long userId) {
        String sortString = sort == null || sort.isBlank() ? DEFAULT_SORT : sort;
        if (!sortString.contains(",") && !sortString.startsWith("-") && !"asc".equalsIgnoreCase(direction)) {
            sortString = "-" + sortString;
        }
        List<SortTerm> terms = SortParser.parse(sortString, bookSortRegistry.registry().keys());
        return BookSortSpecifications.withSort(filter, bookSortRegistry, terms, userId);
    }

    private int validatePageNumber(Integer page) {
        return page != null && page >= 0 ? page : 0;
    }

    private int validatePageSize(Integer size) {
        return size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
    }

    private int validateLimit(Integer limit, int defaultValue) {
        return limit != null && limit > 0 ? Math.min(limit, MAX_PAGE_SIZE) : defaultValue;
    }

    private Specification<BookEntity> buildBaseSpecification(
            Set<Long> accessibleLibraryIds, Long libraryId, Long userId, boolean isAdmin) {
        List<Specification<BookEntity>> specs = new ArrayList<>();
        specs.add(BookSpecifications.notDeleted());
        specs.add(BookSpecifications.hasDigitalFileOrIsPhysical());
        specs.add(contentRestrictions(userId, isAdmin));

        if (accessibleLibraryIds != null) {
            if (libraryId != null && !accessibleLibraryIds.contains(libraryId)) {
                throw ApiError.FORBIDDEN.createException(ACCESS_DENIED_TO_LIBRARY_MESSAGE + libraryId);
            }
            specs.add(libraryId != null
                    ? BookSpecifications.inLibrary(libraryId)
                    : BookSpecifications.inLibraries(accessibleLibraryIds));
        } else if (libraryId != null) {
            specs.add(BookSpecifications.inLibrary(libraryId));
        }

        return BookSpecifications.combine(specs.toArray(Specification[]::new));
    }

    private Specification<BookEntity> contentRestrictions(Long userId, boolean isAdmin) {
        if (isAdmin) {
            return (root, query, cb) -> cb.conjunction();
        }
        return ContentRestrictionSpecification.from(restrictionRepository.findByUserId(userId));
    }

    /**
     * Visibility spec for whole-catalog aggregates (the summary counts). Identical semantics to
     * {@link #buildBaseSpecification} but swaps the "(hasFiles OR isPhysical)" OR for the id-based
     * {@link BookSpecifications#notShellBook} so MariaDB can serve the scan from indexes instead of
     * walking the whole book table per aggregate.
     */
    private Specification<BookEntity> buildAggregateSpecification(
            Set<Long> accessibleLibraryIds, Long userId, boolean isAdmin) {
        return buildAggregateSpecification(accessibleLibraryIds, null, userId, isAdmin);
    }

    private Specification<BookEntity> buildAggregateSpecification(
            Set<Long> accessibleLibraryIds, Long libraryId, Long userId, boolean isAdmin) {
        List<Specification<BookEntity>> specs = new ArrayList<>();
        specs.add(BookSpecifications.notDeleted());
        specs.add(BookSpecifications.notShellBook(findShellBookIds(), MAX_SHELL_IDS_IN_CLAUSE));
        specs.add(contentRestrictions(userId, isAdmin));
        if (accessibleLibraryIds != null) {
            if (libraryId != null && !accessibleLibraryIds.contains(libraryId)) {
                throw ApiError.FORBIDDEN.createException(ACCESS_DENIED_TO_LIBRARY_MESSAGE + libraryId);
            }
            specs.add(libraryId != null
                    ? BookSpecifications.inLibrary(libraryId)
                    : BookSpecifications.inLibraries(accessibleLibraryIds));
        } else if (libraryId != null) {
            specs.add(BookSpecifications.inLibrary(libraryId));
        }
        return BookSpecifications.combine(specs.toArray(Specification[]::new));
    }

    private boolean userHasContentRestrictions(Long userId) {
        return !restrictionRepository.findByUserId(userId).isEmpty();
    }

    private AppPageResponse<AppBookSummary> buildPageResponse(
            Page<BookEntity> bookPage,
            Long userId) {

        List<BookEntity> books = bookPage.getContent();
        Map<Long, UserBookProgressEntity> progressMap = getProgressMapForBooks(userId, books);

        List<AppBookSummary> summaries = books.stream()
                .map(book -> mobileBookMapper.toSummary(book, progressMap.get(book.getId())))
                .toList();

        requestMissingInpxCovers(books);

        return AppPageResponse.fromPage(bookPage, summaries);
    }

    private void requestMissingInpxCovers(List<BookEntity> books) {
        Set<Long> bookIds = books.stream()
                .filter(book -> book.getBookCoverHash() == null)
                .filter(book -> book.getLibrary() != null
                        && book.getLibrary().getSourceType() == LibrarySourceType.INPX)
                .map(BookEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (bookIds.isEmpty()) {
            return;
        }
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user != null && user.getUsername() != null) {
            eventPublisher.publishEvent(new InpxCoverGenerationRequestedEvent(bookIds, user.getUsername()));
        }
    }

    private Map<Long, UserBookProgressEntity> getProgressMapForBooks(Long userId, List<BookEntity> books) {
        if (books.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> bookIds = books.stream()
                .map(BookEntity::getId)
                .collect(Collectors.toSet());
        return getProgressMap(userId, bookIds);
    }

    private List<AppFilterOptions.CountedOption> queryCountedOptions(
            String selectExpr, String joins, String extraWhere,
            String scopeClause, Set<Long> accessibleLibraryIds,
            Long libraryId, Long shelfId) {
        String jpql = "SELECT " + selectExpr + ", COUNT(DISTINCT b.id) FROM BookEntity b"
                + " " + joins
                + " WHERE (b.deleted IS NULL OR b.deleted = false)"
                + (extraWhere.isEmpty() ? "" : " " + extraWhere)
                + " " + scopeClause
                + " GROUP BY " + selectExpr + " ORDER BY COUNT(DISTINCT b.id) DESC";
        var q = entityManager.createQuery(jpql, Tuple.class);
        setFilterQueryParams(q, accessibleLibraryIds, libraryId, shelfId);
        q.setMaxResults(1000);
        return q.getResultList().stream()
                .map(this::mapToCountedOption)
                .toList();
    }

    /**
     * Asserts the caller may read a magic shelf, without materializing the books it matches.
     * {@code toSpecification} throws when the shelf is missing or forbidden; its returned
     * Specification is deliberately unused here.
     */
    private void assertMagicShelfAccessible(Long userId, Long magicShelfId) {
        magicShelfBookService.toSpecification(userId, magicShelfId);
    }

    private String buildScopeClause(String libraryClause, String shelfClause) {
        var sb = new StringBuilder();
        if (!libraryClause.isEmpty()) sb.append(" ").append(libraryClause);
        if (!shelfClause.isEmpty()) sb.append(" ").append(shelfClause);
        return sb.toString();
    }

    /**
     * Books with no files that are not physical either would render as empty shells. The per-query
     * predicate "(b.hasFiles = true OR b.isPhysical = true)" excluded them, but repeating that OR
     * in every facet aggregate still forces MariaDB through the whole book table per aggregate.
     * The set is almost always empty, so it is resolved once (globally cached, denormalized
     * has_files flag) and the aggregates only pay for it when shells actually exist.
     */
    private Set<Long> findShellBookIds() {
        return shellBookIdsCache.get(() -> Set.copyOf(entityManager.createQuery(
                "SELECT b.id FROM BookEntity b"
                        + " WHERE b.hasFiles = false AND (b.isPhysical IS NULL OR b.isPhysical = false)",
                Long.class).getResultList()));
    }

    private String visibilityClause(Set<Long> shellBookIds) {
        if (shellBookIds.isEmpty()) {
            return "";
        }
        if (shellBookIds.size() > MAX_SHELL_IDS_IN_CLAUSE) {
            return " AND (b.hasFiles = true OR b.isPhysical = true)";
        }
        return " AND b.id NOT IN (" + shellBookIds.stream().sorted()
                .map(String::valueOf).collect(Collectors.joining(", ")) + ")";
    }

    private void setFilterQueryParams(Query query, Set<Long> accessibleLibraryIds, Long libraryId, Long shelfId) {
        if (libraryId != null) {
            query.setParameter(PARAM_LIBRARY_ID, libraryId);
        } else if (accessibleLibraryIds != null) {
            query.setParameter("libraryIds", accessibleLibraryIds);
        }
        if (shelfId != null) {
            query.setParameter("shelfId", shelfId);
        }
        Set<String> parameterNames = query.getParameters().stream()
                .map(Parameter::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        BookLoreUser currentUser = authenticationService.getAuthenticatedUser();
        AppContentRestrictionQueryService.RestrictionQueryScope restrictionScope = currentUser.getPermissions().isAdmin()
                ? AppContentRestrictionQueryService.RestrictionQueryScope.empty()
                : restrictionQueryScope(currentUser.getId());
        restrictionScope.parameters()
                .forEach((name, value) -> {
                    if (parameterNames.contains(name)) {
                        query.setParameter(name, value);
                    }
                });
    }

    private AppContentRestrictionQueryService.RestrictionQueryScope restrictionQueryScope(Long userId) {
        return restrictionQueryService.scopeForUser(userId);
    }

    private List<AppFilterOptions.CountedOption> queryReadStatusCounts(
            Long userId, String scopeClause, Set<Long> accessibleLibraryIds,
            Long libraryId, Long shelfId) {
        String jpql = "SELECT ubp.readStatus, COUNT(DISTINCT ubp.book.id) FROM UserBookProgressEntity ubp"
                + " WHERE ubp.user.id = :userId"
                + " AND ubp.readStatus <> org.booklore.model.enums.ReadStatus.UNSET"
                + " AND ubp.book.id IN ("
                + "   SELECT b.id FROM BookEntity b"
                + "   WHERE (b.deleted IS NULL OR b.deleted = false)"
                + " " + scopeClause
                + " )"
                + " GROUP BY ubp.readStatus ORDER BY COUNT(DISTINCT ubp.book.id) DESC";
        var q = entityManager.createQuery(jpql, Tuple.class);
        q.setParameter(PARAM_USER_ID, userId);
        setFilterQueryParams(q, accessibleLibraryIds, libraryId, shelfId);
        List<AppFilterOptions.CountedOption> options = q.getResultList().stream()
                .map(t -> new AppFilterOptions.CountedOption(
                        t.get(0, ReadStatus.class).name(),
                        t.get(1, Long.class)))
                .toList();

        String baseQuery = "SELECT COUNT(DISTINCT b.id) FROM BookEntity b"
                + " WHERE (b.deleted IS NULL OR b.deleted = false)"
                + " AND b.id NOT IN ("
                + "   SELECT ubp.book.id FROM UserBookProgressEntity ubp WHERE ubp.user.id = :userId"
                + " )"
                + " " + scopeClause;
        var baseQ = entityManager.createQuery(baseQuery, Long.class);
        baseQ.setParameter(PARAM_USER_ID, userId);
        setFilterQueryParams(baseQ, accessibleLibraryIds, libraryId, shelfId);
        long unsetCount = baseQ.getSingleResult();

        if (unsetCount > 0) {
            List<AppFilterOptions.CountedOption> mutable = new ArrayList<>(options);
            mutable.addFirst(new AppFilterOptions.CountedOption("UNSET", unsetCount));
            return List.copyOf(mutable);
        }
        return options;
    }

    private List<AppFilterOptions.CountedOption> queryRatingBuckets(
            String ratingExpr, String scopeClause, Set<Long> accessibleLibraryIds,
            Long libraryId, Long shelfId) {
        return queryGroupedCount(
                "CASE " +
                "  WHEN " + ratingExpr + " >= 4.5 THEN '5' " +
                "  WHEN " + ratingExpr + " >= 4.0 THEN '4' " +
                "  WHEN " + ratingExpr + " >= 3.0 THEN '3' " +
                "  WHEN " + ratingExpr + " >= 2.0 THEN '2' " +
                "  WHEN " + ratingExpr + " >= 1.0 THEN '1' " +
                "  ELSE '0' " +
                "END",
                METADATA_JOIN_CLAUSE, "AND " + ratingExpr + " IS NOT NULL",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);
    }

    private List<AppFilterOptions.CountedOption> queryGroupedCount(
            String caseExpr, String joins, String extraWhere,
            String scopeClause, Set<Long> accessibleLibraryIds,
            Long libraryId, Long shelfId) {
        String jpql = "SELECT " + caseExpr + ", COUNT(DISTINCT b.id) FROM BookEntity b " + joins +
                " WHERE (b.deleted IS NULL OR b.deleted = false) "
                + extraWhere + " " + scopeClause +
                " GROUP BY 1";
        var q = entityManager.createQuery(jpql, Tuple.class);
        setFilterQueryParams(q, accessibleLibraryIds, libraryId, shelfId);
        return q.getResultList().stream()
                .filter(t -> t.get(0) != null)
                .map(this::mapToCountedOption)
                .toList();
    }

    private AppFilterOptions.CountedOption mapToCountedOption(Tuple t) {
        Object val = t.get(0);
        return new AppFilterOptions.CountedOption(countedOptionName(val), t.get(1, Long.class));
    }

    private static String countedOptionName(Object val) {
        if (val instanceof Enum<?> e) {
            return e.name();
        }
        return val != null ? String.valueOf(val) : null;
    }

    private static String creatorRoleLabel(ComicCreatorRole role) {
        return switch (role) {
            case PENCILLER -> "penciller";
            case INKER -> "inker";
            case COLORIST -> "colorist";
            case LETTERER -> "letterer";
            case COVER_ARTIST -> "coverArtist";
            case EDITOR -> "editor";
        };
    }
}
