package org.booklore.app.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.browse.SortParser;
import org.booklore.browse.SortTerm;
import org.booklore.exception.ApiError;
import org.booklore.app.dto.AppBookDetail;
import org.booklore.app.dto.AppBookProgressResponse;
import org.booklore.app.dto.AppBookSummary;
import org.booklore.app.dto.AppCatalogSummary;
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
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

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
                          FilterOptionsCache filterOptionsCache) {
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
            return buildPageResponse(bookPage, userId, pageNum, pageSize);
        }

        Pageable pageable = PageRequest.of(pageNum, pageSize);

        Specification<BookEntity> spec = buildSpecification(
                accessibleLibraryIds, userId, user.getPermissions().isAdmin(), req);

        if (Boolean.TRUE.equals(req.unshelved())) {
            spec = spec.and(BookSpecifications.unshelved());
        }
        spec = withSort(spec, req.sort(), req.dir(), userId);

        Page<BookEntity> bookPage = bookRepository.findAll(spec, pageable);
        return buildPageResponse(bookPage, userId, pageNum, pageSize);
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

    static String catalogSummaryKey(Long userId, boolean admin, Set<Long> accessibleLibraryIds) {
        String libraries = accessibleLibraryIds == null
                ? "*"
                : accessibleLibraryIds.stream().sorted()
                        .map(String::valueOf).collect(Collectors.joining(","));
        return userId + "|" + admin + "|" + libraries;
    }

    private AppCatalogSummary computeCatalogSummary(BookLoreUser user, Set<Long> accessibleLibraryIds, boolean admin) {
        Long userId = user.getId();
        Specification<BookEntity> visibleBooks = buildBaseSpecification(
                accessibleLibraryIds, null, userId, admin);

        long totalBooks = bookRepository.count(visibleBooks);
        long totalAuthors = countDistinctAuthors(visibleBooks);
        long totalSeries = countDistinctSeries(visibleBooks);
        long unshelvedBooks = bookRepository.count(visibleBooks.and(BookSpecifications.unshelved()));
        return new AppCatalogSummary(
                totalBooks,
                totalAuthors,
                totalSeries,
                unshelvedBooks,
                countBooksByLibrary(visibleBooks));
    }

    public long getCatalogBookCount() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        return bookRepository.count(buildBaseSpecification(
                getAccessibleLibraryIds(user), null, user.getId(), user.getPermissions().isAdmin()));
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

    private long countDistinctAuthors(Specification<BookEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> root = query.from(BookEntity.class);
        query.select(cb.countDistinct(root.join("metadata").join("authors").get("id")));
        query.where(spec.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getSingleResult();
    }

    private long countDistinctSeries(Specification<BookEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> root = query.from(BookEntity.class);
        var seriesName = root.join("metadata").get("seriesName");
        query.select(cb.countDistinct(seriesName));
        query.where(cb.and(
                spec.toPredicate(root, query, cb),
                cb.isNotNull(seriesName),
                cb.notEqual(seriesName, "")));
        return entityManager.createQuery(query).getSingleResult();
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
                    .setParameter("libraryId", libraryId)
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
        return buildPageResponse(bookPage, userId, pageNum, pageSize);
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

        return buildPageResponse(bookPage, userId, pageNum, pageSize);
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
                () -> computeFilterOptions(user, userId, accessibleLibraryIds, libraryId, shelfId));
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
            libraryClause = "AND b.library.id = :libraryId";
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
                "bf.bookType", "JOIN b.bookFiles bf", "AND bf.isBookFormat = true",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> categories = queryCountedOptions(
                "c.name", "JOIN b.metadata m JOIN m.categories c", "",
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
                "t.name", "JOIN b.metadata m JOIN m.tags t", "",
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
                "JOIN b.bookFiles bf", "AND bf.isBookFormat = true",
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
                "c.name", "JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.characters c", "",
                scopeClause, accessibleLibraryIds, libraryId, shelfId);

        List<AppFilterOptions.CountedOption> comicTeams = queryCountedOptions(
                "t.name", "JOIN b.metadata m JOIN m.comicMetadata cm JOIN cm.teams t", "",
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

        if (req.shelfId() != null) {
            ShelfEntity shelf = shelfRepository.findById(req.shelfId())
                    .orElseThrow(() -> ApiError.SHELF_NOT_FOUND.createException(req.shelfId()));
            if (!shelf.isPublic() && !shelf.getUser().getId().equals(userId)) {
                throw ApiError.FORBIDDEN.createException("Access denied to shelf " + req.shelfId());
            }
            specs.add(BookSpecifications.inShelf(req.shelfId()));
        }

        if (req.search() != null && !req.search().trim().isEmpty()) {
            specs.add(BookSpecifications.searchText(req.search()));
        }

        int filterStartIndex = specs.size();

        if (req.status() != null && !req.status().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.status());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withReadStatuses(cleaned, userId, req.effectiveFilterMode()));
            }
        }

        if (req.fileType() != null && !req.fileType().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.fileType());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withFileTypes(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.minRating() != null) {
            specs.add(BookSpecifications.withMinRating(req.minRating(), userId));
        }

        if (req.maxRating() != null) {
            specs.add(BookSpecifications.withMaxRating(req.maxRating(), userId));
        }

        if (req.authors() != null && !req.authors().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.authors());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withAuthors(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.language() != null && !req.language().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.language());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withLanguages(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.series() != null && !req.series().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.series());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.inSeriesMulti(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.category() != null && !req.category().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.category());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withCategories(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.publisher() != null && !req.publisher().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.publisher());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withPublishers(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.tag() != null && !req.tag().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.tag());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withTags(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.mood() != null && !req.mood().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.mood());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withMoods(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.narrator() != null && !req.narrator().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.narrator());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withNarrators(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.ageRating() != null && !req.ageRating().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.ageRating());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withAgeRatings(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.contentRating() != null && !req.contentRating().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.contentRating());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withContentRatings(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.matchScore() != null && !req.matchScore().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.matchScore());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withMatchScores(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.publishedDate() != null && !req.publishedDate().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.publishedDate());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withPublishedYears(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.fileSize() != null && !req.fileSize().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.fileSize());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withFileSizes(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.personalRating() != null && !req.personalRating().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.personalRating());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withPersonalRatings(cleaned, userId, req.effectiveFilterMode()));
            }
        }

        if (req.amazonRating() != null && !req.amazonRating().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.amazonRating());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withAmazonRatings(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.goodreadsRating() != null && !req.goodreadsRating().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.goodreadsRating());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withGoodreadsRatings(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.hardcoverRating() != null && !req.hardcoverRating().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.hardcoverRating());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withHardcoverRatings(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.lubimyczytacRating() != null && !req.lubimyczytacRating().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.lubimyczytacRating());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withLubimyczytacRatings(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.ranobedbRating() != null && !req.ranobedbRating().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.ranobedbRating());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withRanobedbRatings(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.audibleRating() != null && !req.audibleRating().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.audibleRating());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withAudibleRatings(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.pageCount() != null && !req.pageCount().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.pageCount());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withPageCounts(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.shelfStatus() != null && !req.shelfStatus().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.shelfStatus());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withShelfStatus(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.comicCharacter() != null && !req.comicCharacter().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.comicCharacter());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withComicCharacters(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.comicTeam() != null && !req.comicTeam().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.comicTeam());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withComicTeams(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.comicLocation() != null && !req.comicLocation().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.comicLocation());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withComicLocations(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.comicCreator() != null && !req.comicCreator().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.comicCreator());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.withComicCreators(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.shelves() != null && !req.shelves().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.shelves());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.inShelves(cleaned, req.effectiveFilterMode()));
            }
        }

        if (req.libraries() != null && !req.libraries().isEmpty()) {
            List<String> cleaned = BookListRequest.cleanValues(req.libraries());
            if (!cleaned.isEmpty()) {
                specs.add(BookSpecifications.inLibraries(cleaned, req.effectiveFilterMode()));
            }
        }

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

    private AppPageResponse<AppBookSummary> buildPageResponse(
            Page<BookEntity> bookPage,
            Long userId,
            int pageNum,
            int pageSize) {

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
     * Books with no files that are not physical either would render as empty shells. The legacy
     * per-query predicate "(b.bookFiles IS NOT EMPTY OR b.isPhysical = true)" excluded them, but
     * MariaDB executes that OR-EXISTS as a materialized scan of the whole book_file index in every
     * facet aggregate. The set is almost always empty, so it is computed once per facet
     * recomputation and the aggregates only pay for it when shells actually exist.
     */
    private Set<Long> findShellBookIds() {
        return Set.copyOf(entityManager.createQuery(
                "SELECT b.id FROM BookEntity b"
                        + " WHERE b.bookFiles IS EMPTY AND (b.isPhysical IS NULL OR b.isPhysical = false)",
                Long.class).getResultList());
    }

    private String visibilityClause(Set<Long> shellBookIds) {
        if (shellBookIds.isEmpty()) {
            return "";
        }
        if (shellBookIds.size() > MAX_SHELL_IDS_IN_CLAUSE) {
            return " AND (b.bookFiles IS NOT EMPTY OR b.isPhysical = true)";
        }
        return " AND b.id NOT IN (" + shellBookIds.stream().sorted()
                .map(String::valueOf).collect(Collectors.joining(", ")) + ")";
    }

    private void setFilterQueryParams(Query query, Set<Long> accessibleLibraryIds, Long libraryId, Long shelfId) {
        if (libraryId != null) {
            query.setParameter("libraryId", libraryId);
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
        String name = val instanceof Enum<?> e ? e.name() : val != null ? String.valueOf(val) : null;
        return new AppFilterOptions.CountedOption(name, t.get(1, Long.class));
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
