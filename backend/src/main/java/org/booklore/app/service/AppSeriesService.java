package org.booklore.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.AllArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.app.dto.*;
import org.booklore.app.mapper.AppBookMapper;
import org.booklore.service.browse.BookSpecifications;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.*;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.BookRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.persistence.Query;

@Service
@AllArgsConstructor
public class AppSeriesService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String PARAM_USER_ID = "userId";
    private static final String STATUS_IN_PROGRESS = "in-progress";
    private static final String HAVING = " HAVING ";
    private static final String MULTI_BOOK_SERIES_EXPR = "COUNT(b.id) > 1";
    private static final String READ_COUNT_EXPR = "SUM(CASE WHEN p.readStatus = org.booklore.model.enums.ReadStatus.READ THEN 1 ELSE 0 END)";
    private static final String READING_COUNT_EXPR = "SUM(CASE WHEN p.readStatus IN (org.booklore.model.enums.ReadStatus.READING, org.booklore.model.enums.ReadStatus.RE_READING, org.booklore.model.enums.ReadStatus.PAUSED) THEN 1 ELSE 0 END)";
    private static final String ABANDONED_COUNT_EXPR = "SUM(CASE WHEN p.readStatus = org.booklore.model.enums.ReadStatus.ABANDONED THEN 1 ELSE 0 END)";
    private static final String WONT_READ_COUNT_EXPR = "SUM(CASE WHEN p.readStatus = org.booklore.model.enums.ReadStatus.WONT_READ THEN 1 ELSE 0 END)";

    private final EntityManager entityManager;
    private final AuthenticationService authenticationService;
    private final BookRepository bookRepository;
    private final UserBookProgressRepository userBookProgressRepository;
    private final AppBookMapper mobileBookMapper;

    @Transactional(readOnly = true)
    public AppPageResponse<AppSeriesSummary> getSeries(
            Integer page,
            Integer size,
            String sortBy,
            String sortDir,
            Long libraryId,
            String search,
            String statusFilter) {

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        LibraryAccessScope libraryAccessScope = getLibraryAccessScope(user);

        if (libraryId != null) {
            validateLibraryAccess(libraryAccessScope, libraryId);
        }

        int pageNum = page != null && page >= 0 ? page : 0;
        int pageSize = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        // Build WHERE clause fragments
        String libraryClause = buildLibraryClause(libraryAccessScope, libraryId);
        final String searchParam = "searchPattern";
        String searchPattern = (search != null && !search.trim().isEmpty())
                ? "%" + search.trim().toLowerCase() + "%"
                : null;
        String searchClause = searchPattern != null
                ? " AND LOWER(m.seriesName) LIKE :searchPattern"
                : "";

        String normalizedStatus = normalizeStatusFilter(statusFilter);
        String havingClause = buildSeriesStatusHavingClause(normalizedStatus);
        boolean aggregateProgress = requiresProgressAggregate(sortBy, normalizedStatus);

        String orderBy = buildSeriesOrderBy(sortBy, sortDir, normalizedStatus);

        // Phase 1: Aggregate query
        String progressSelect = aggregateProgress
                ? ", " + READ_COUNT_EXPR + ", MAX(p.lastReadTime), " + READING_COUNT_EXPR
                + ", " + ABANDONED_COUNT_EXPR + ", " + WONT_READ_COUNT_EXPR
                : "";
        String progressJoin = aggregateProgress
                ? " LEFT JOIN b.userBookProgress p ON p.user.id = :userId"
                : "";
        String aggregateQuery = "SELECT m.seriesName, COUNT(b.id), MAX(m.seriesTotal), MAX(b.addedOn)"
                + progressSelect
                + " FROM BookEntity b JOIN b.metadata m"
                + progressJoin
                + " WHERE (b.deleted IS NULL OR b.deleted = false)"
                + " AND (b.hasFiles = true OR b.isPhysical = true)"
                + " AND m.seriesName IS NOT NULL"
                + libraryClause
                + searchClause
                + " GROUP BY m.seriesName"
                + havingClause
                + " ORDER BY " + orderBy;

        var aggregateQ = entityManager.createQuery(aggregateQuery, Tuple.class);
        if (aggregateProgress) {
            aggregateQ.setParameter(PARAM_USER_ID, userId);
        }
        setLibraryParams(aggregateQ, libraryAccessScope, libraryId);
        if (searchPattern != null) {
            aggregateQ.setParameter(searchParam, searchPattern);
        }
        aggregateQ.setFirstResult(pageNum * pageSize);
        aggregateQ.setMaxResults(pageSize);

        List<Tuple> aggregateResults = aggregateQ.getResultList();

        long totalElements = countSeries(normalizedStatus, libraryAccessScope, libraryId, userId,
                searchPattern, searchClause, libraryClause);

        return buildSeriesPage(aggregateResults, libraryAccessScope, libraryId, userId,
                aggregateProgress, pageNum, pageSize, totalElements);
    }

    private long countSeries(String statusFilter, LibraryAccessScope libraryAccessScope, Long libraryId, Long userId,
                             String searchPattern, String searchClause, String libraryClause) {
        final String searchParam = "searchPattern";
        String havingClause = buildSeriesStatusHavingClause(statusFilter);
        String progressJoin = statusFilter != null
                ? " LEFT JOIN b.userBookProgress p ON p.user.id = :userId"
                : "";
        String countQuery = "SELECT COUNT(*) FROM ("
                + "SELECT m.seriesName AS seriesName FROM BookEntity b JOIN b.metadata m"
                + progressJoin
                + " WHERE (b.deleted IS NULL OR b.deleted = false)"
                + " AND (b.hasFiles = true OR b.isPhysical = true)"
                + " AND m.seriesName IS NOT NULL"
                + libraryClause
                + searchClause
                + " GROUP BY m.seriesName"
                + havingClause
                + ") groupedSeries";

        var countQ = entityManager.createQuery(countQuery, Long.class);
        if (statusFilter != null) {
            countQ.setParameter(PARAM_USER_ID, userId);
        }
        setLibraryParams(countQ, libraryAccessScope, libraryId);
        if (searchPattern != null) {
            countQ.setParameter(searchParam, searchPattern);
        }
        return countQ.getSingleResult();
    }

    private AppPageResponse<AppSeriesSummary> buildSeriesPage(
            List<Tuple> aggregateResults,
            LibraryAccessScope libraryAccessScope,
            Long libraryId,
            Long userId,
            boolean aggregateProgress,
            int pageNum,
            int pageSize,
            long totalElements) {

        if (aggregateResults.isEmpty()) {
            return AppPageResponse.of(Collections.emptyList(), pageNum, pageSize, totalElements);
        }

        List<String> seriesNames = aggregateResults.stream()
                .map(t -> t.get(0, String.class))
                .toList();

        // Phase 2: Fetch books for enrichment (only ToOne joins; collections loaded via @BatchSize)
        String libraryClause = buildLibraryClause(libraryAccessScope, libraryId);
        String booksQuery = "SELECT b FROM BookEntity b"
                + " JOIN FETCH b.metadata m"
                + " WHERE m.seriesName IN :seriesNames"
                + " AND (b.deleted IS NULL OR b.deleted = false)"
                + " AND (b.hasFiles = true OR b.isPhysical = true)"
                + libraryClause;

        var booksQ = entityManager.createQuery(booksQuery, BookEntity.class);
        booksQ.setParameter("seriesNames", seriesNames);
        setLibraryParams(booksQ, libraryAccessScope, libraryId);

        List<BookEntity> books = booksQ.getResultList();

        // Group books by series name
        Map<String, List<BookEntity>> booksBySeries = books.stream()
                .collect(Collectors.groupingBy(b -> b.getMetadata().getSeriesName()));

        // Build aggregates map from Phase 1
        Map<String, Tuple> aggregateMap = new LinkedHashMap<>();
        for (Tuple t : aggregateResults) {
            aggregateMap.put(t.get(0, String.class), t);
        }
        Map<String, SeriesProgressCounts> progressBySeries = aggregateProgress
                ? Collections.emptyMap()
                : fetchSeriesProgress(seriesNames, libraryAccessScope, libraryId, userId);

        // Merge into summaries, preserving Phase 1 order
        List<AppSeriesSummary> summaries = new ArrayList<>();
        for (String seriesName : seriesNames) {
            Tuple agg = aggregateMap.get(seriesName);
            List<BookEntity> seriesBooks = booksBySeries.getOrDefault(seriesName, Collections.emptyList());

            // Distinct authors across all books in series
            List<String> authors = seriesBooks.stream()
                    .filter(b -> b.getMetadata() != null && b.getMetadata().getAuthors() != null)
                    .flatMap(b -> b.getMetadata().getAuthors().stream())
                    .map(AuthorEntity::getName)
                    .distinct()
                    .toList();

            // Cover books sorted by seriesNumber ASC nulls last
            List<SeriesCoverBook> coverBooks = seriesBooks.stream()
                    .sorted(Comparator.comparing(
                            (BookEntity b) -> b.getMetadata().getSeriesNumber(),
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(b -> {
                        BookFileEntity primaryFile = b.getPrimaryBookFile();
                        String fileType = (primaryFile != null && primaryFile.getBookType() != null)
                                ? primaryFile.getBookType().name()
                                : null;
                        return SeriesCoverBook.builder()
                                .bookId(b.getId())
                                .coverUpdatedOn(b.getMetadata().getCoverUpdatedOn())
                                .seriesNumber(b.getMetadata().getSeriesNumber())
                                .primaryFileType(fileType)
                                .build();
                    })
                    .toList();

            SeriesProgressCounts progress = aggregateProgress
                    ? progressFromAggregate(agg)
                    : progressBySeries.getOrDefault(seriesName, SeriesProgressCounts.EMPTY);
            int booksRead = progress.booksRead();
            Instant lastReadTime = progress.lastReadTime();
            int readingCount = progress.readingCount();
            int abandonedCount = progress.abandonedCount();
            int wontReadCount = progress.wontReadCount();
            int bookCount = agg.get(1, Long.class).intValue();

            summaries.add(AppSeriesSummary.builder()
                    .seriesName(agg.get(0, String.class))
                    .bookCount(bookCount)
                    .seriesTotal(agg.get(2, Integer.class))
                    .latestAddedOn(agg.get(3, Instant.class))
                    .lastReadTime(lastReadTime)
                    .booksRead(booksRead)
                    .seriesStatus(computeSeriesStatus(bookCount, booksRead, readingCount, abandonedCount, wontReadCount).name())
                    .authors(authors)
                    .coverBooks(coverBooks)
                    .build());
        }

        return AppPageResponse.of(summaries, pageNum, pageSize, totalElements);
    }

    private Map<String, SeriesProgressCounts> fetchSeriesProgress(
            List<String> seriesNames,
            LibraryAccessScope libraryAccessScope,
            Long libraryId,
            Long userId) {
        String libraryClause = buildLibraryClause(libraryAccessScope, libraryId);
        String progressQuery = "SELECT m.seriesName, " + READ_COUNT_EXPR + ", MAX(p.lastReadTime), "
                + READING_COUNT_EXPR + ", " + ABANDONED_COUNT_EXPR + ", " + WONT_READ_COUNT_EXPR
                + " FROM UserBookProgressEntity p JOIN p.book b JOIN b.metadata m"
                + " WHERE p.user.id = :userId"
                + " AND m.seriesName IN :seriesNames"
                + " AND (b.deleted IS NULL OR b.deleted = false)"
                + " AND (b.hasFiles = true OR b.isPhysical = true)"
                + libraryClause
                + " GROUP BY m.seriesName";

        var progressQ = entityManager.createQuery(progressQuery, Tuple.class);
        progressQ.setParameter(PARAM_USER_ID, userId);
        progressQ.setParameter("seriesNames", seriesNames);
        setLibraryParams(progressQ, libraryAccessScope, libraryId);

        return progressQ.getResultList().stream()
                .collect(Collectors.toMap(
                        tuple -> tuple.get(0, String.class),
                        this::progressFromProgressQuery
                ));
    }

    @Transactional(readOnly = true)
    public AppPageResponse<AppBookSummary> getSeriesBooks(
            String seriesName,
            Integer page,
            Integer size,
            String sortBy,
            String sortDir,
            Long libraryId) {

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        LibraryAccessScope libraryAccessScope = getLibraryAccessScope(user);

        if (libraryId != null) {
            validateLibraryAccess(libraryAccessScope, libraryId);
        }

        int pageNum = page != null && page >= 0 ? page : 0;
        int pageSize = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        Sort sort = buildBookSort(sortBy, sortDir);
        Pageable pageable = PageRequest.of(pageNum, pageSize, sort);

        Specification<BookEntity> spec = buildSeriesBooksSpec(libraryAccessScope, libraryId, seriesName);

        Page<BookEntity> bookPage = bookRepository.findAll(spec, pageable);

        Set<Long> bookIds = bookPage.getContent().stream()
                .map(BookEntity::getId)
                .collect(Collectors.toSet());
        Map<Long, UserBookProgressEntity> progressMap = getProgressMap(userId, bookIds);

        List<AppBookSummary> summaries = bookPage.getContent().stream()
                .map(book -> mobileBookMapper.toSummary(book, progressMap.get(book.getId())))
                .toList();

        return AppPageResponse.of(summaries, pageNum, pageSize, bookPage.getTotalElements());
    }

    // --- Access control helpers (duplicated from AppBookService to minimize blast radius) ---

    private LibraryAccessScope getLibraryAccessScope(BookLoreUser user) {
        if (user.getPermissions().isAdmin()) {
            return new LibraryAccessScope(true, Collections.emptySet());
        }
        if (user.getAssignedLibraries() == null || user.getAssignedLibraries().isEmpty()) {
            return new LibraryAccessScope(false, Collections.emptySet());
        }
        Set<Long> libraryIds = user.getAssignedLibraries().stream()
                .map(Library::getId)
                .collect(Collectors.toSet());
        return new LibraryAccessScope(false, libraryIds);
    }

    private void validateLibraryAccess(LibraryAccessScope libraryAccessScope, Long libraryId) {
        if (!libraryAccessScope.allLibraries() && !libraryAccessScope.libraryIds().contains(libraryId)) {
            throw ApiError.FORBIDDEN.createException("Access denied to library " + libraryId);
        }
    }

    // --- Query helpers ---

    private String buildLibraryClause(LibraryAccessScope libraryAccessScope, Long libraryId) {
        return buildLibraryClause(libraryAccessScope, libraryId, "b");
    }

    private String buildLibraryClause(LibraryAccessScope libraryAccessScope, Long libraryId, String bookAlias) {
        if (libraryId != null) {
            return " AND " + bookAlias + ".library.id = :libraryId";
        } else if (!libraryAccessScope.allLibraries()) {
            return " AND " + bookAlias + ".library.id IN :libraryIds";
        }
        return "";
    }

    private void setLibraryParams(Query query, LibraryAccessScope libraryAccessScope, Long libraryId) {
        if (libraryId != null) {
            query.setParameter("libraryId", libraryId);
        } else if (!libraryAccessScope.allLibraries()) {
            query.setParameter("libraryIds", libraryAccessScope.libraryIds());
        }
    }

    private String buildSeriesOrderBy(String sortBy, String sortDir, String statusFilter) {
        String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String nullsClause = "ASC".equals(dir) ? " NULLS LAST" : " NULLS FIRST";

        return switch (sortBy != null ? sortBy.toLowerCase() : "") {
            case "name" -> "m.seriesName " + dir;
            case "bookcount" -> "COUNT(b.id) " + dir;
            case "readprogress" -> "(" + READ_COUNT_EXPR + " * 1.0 / COUNT(b.id)) " + dir;
            case "lastreadtime" -> "MAX(p.lastReadTime) " + dir + nullsClause;
            default -> {
                if (STATUS_IN_PROGRESS.equals(statusFilter)) {
                    yield "MAX(p.lastReadTime) " + dir + nullsClause;
                }
                yield "MAX(b.addedOn) " + dir + nullsClause;
            }
        };
    }

    private boolean requiresProgressAggregate(String sortBy, String statusFilter) {
        if (statusFilter != null) {
            return true;
        }
        String normalizedSort = sortBy != null ? sortBy.toLowerCase(Locale.ROOT) : "";
        return "readprogress".equals(normalizedSort) || "lastreadtime".equals(normalizedSort);
    }

    private String normalizeStatusFilter(String statusFilter) {
        if (statusFilter == null) {
            return null;
        }
        String normalized = statusFilter.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "not-started", STATUS_IN_PROGRESS, "completed", "abandoned" -> normalized;
            default -> null;
        };
    }

    private String buildSeriesStatusHavingClause(String statusFilter) {
        String minimumBookCount = HAVING + MULTI_BOOK_SERIES_EXPR;
        if (statusFilter == null) {
            return minimumBookCount;
        }
        String abandonedTotalExpr = "(" + ABANDONED_COUNT_EXPR + " + " + WONT_READ_COUNT_EXPR + ")";
        return switch (statusFilter) {
            case "not-started" -> minimumBookCount + " AND " + READ_COUNT_EXPR + " = 0 AND " + READING_COUNT_EXPR
                    + " = 0 AND " + abandonedTotalExpr + " = 0";
            case STATUS_IN_PROGRESS -> minimumBookCount + " AND " + abandonedTotalExpr + " = 0 AND ("
                    + READING_COUNT_EXPR + " > 0 OR (" + READ_COUNT_EXPR + " > 0 AND "
                    + READ_COUNT_EXPR + " < COUNT(b.id)))";
            case "completed" -> minimumBookCount + " AND " + READ_COUNT_EXPR + " = COUNT(b.id)";
            case "abandoned" -> minimumBookCount + " AND " + abandonedTotalExpr + " > 0";
            default -> minimumBookCount;
        };
    }

    private int tupleLongAsInt(Tuple tuple, int index) {
        Long value = tuple.get(index, Long.class);
        return value == null ? 0 : value.intValue();
    }

    private SeriesProgressCounts progressFromAggregate(Tuple tuple) {
        return new SeriesProgressCounts(
                tupleLongAsInt(tuple, 4),
                tuple.get(5, Instant.class),
                tupleLongAsInt(tuple, 6),
                tupleLongAsInt(tuple, 7),
                tupleLongAsInt(tuple, 8)
        );
    }

    private SeriesProgressCounts progressFromProgressQuery(Tuple tuple) {
        return new SeriesProgressCounts(
                tupleLongAsInt(tuple, 1),
                tuple.get(2, Instant.class),
                tupleLongAsInt(tuple, 3),
                tupleLongAsInt(tuple, 4),
                tupleLongAsInt(tuple, 5)
        );
    }

    private ReadStatus computeSeriesStatus(int bookCount, int booksRead, int readingCount, int abandonedCount, int wontReadCount) {
        if (wontReadCount > 0) return ReadStatus.WONT_READ;
        if (abandonedCount > 0) return ReadStatus.ABANDONED;
        if (bookCount > 0 && booksRead == bookCount) return ReadStatus.READ;
        if (readingCount > 0) return ReadStatus.READING;
        if (booksRead > 0) return ReadStatus.PARTIALLY_READ;
        return ReadStatus.UNREAD;
    }

    private Sort buildBookSort(String sortBy, String sortDir) {
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String field = switch (sortBy != null ? sortBy.toLowerCase() : "") {
            case "title" -> "metadata.title";
            case "seriesnumber" -> "metadata.seriesNumber";
            case "recentlyadded" -> "addedOn";
            default -> "metadata.seriesNumber";
        };
        return Sort.by(direction, field);
    }

    private Specification<BookEntity> buildSeriesBooksSpec(LibraryAccessScope libraryAccessScope, Long libraryId, String seriesName) {
        List<Specification<BookEntity>> specs = new ArrayList<>();
        specs.add(BookSpecifications.notDeleted());
        specs.add(BookSpecifications.hasDigitalFileOrIsPhysical());
        specs.add(BookSpecifications.inSeries(seriesName));

        if (libraryId != null) {
            specs.add(BookSpecifications.inLibrary(libraryId));
        } else if (!libraryAccessScope.allLibraries()) {
            specs.add(BookSpecifications.inLibraries(libraryAccessScope.libraryIds()));
        }

        return BookSpecifications.combine(specs.toArray(Specification[]::new));
    }

    private record LibraryAccessScope(boolean allLibraries, Set<Long> libraryIds) {
    }

    private record SeriesProgressCounts(
            int booksRead,
            Instant lastReadTime,
            int readingCount,
            int abandonedCount,
            int wontReadCount) {
        private static final SeriesProgressCounts EMPTY = new SeriesProgressCounts(0, null, 0, 0, 0);
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
}
