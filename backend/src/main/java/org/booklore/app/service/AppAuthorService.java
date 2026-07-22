package org.booklore.app.service;

import lombok.AllArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.app.dto.AppAuthorDetail;
import org.booklore.app.dto.AppAuthorSummary;
import org.booklore.app.dto.AppPageResponse;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.repository.AuthorRepository;
import org.booklore.service.AuthorPhotoIndex;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class AppAuthorService {

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String PARAM_USER_ID = "userId";
    private static final String PHOTO_IDS_PARAM = ":photoIds";
    private static final String COL_A_NAME = "a.name";
    private static final String BOOK_COUNT_EXPR = "COUNT(DISTINCT b.id)";
    private static final String READ_COUNT_EXPR = "SUM(CASE WHEN p.readStatus = org.booklore.model.enums.ReadStatus.READ THEN 1 ELSE 0 END)";
    private static final String IN_PROGRESS_COUNT_EXPR = "SUM(CASE WHEN p.readStatus IN (org.booklore.model.enums.ReadStatus.READING, org.booklore.model.enums.ReadStatus.RE_READING) THEN 1 ELSE 0 END)";

    private final AuthorRepository authorRepository;
    private final AuthenticationService authenticationService;
    private final AuthorPhotoIndex authorPhotoIndex;
    private final EntityManager entityManager;
    private final FileService fileService;

    // Delegating overload: this method is itself @Transactional(readOnly = true), so the self-invoked
    // full overload runs inside an active read-only transaction; the proxy bypass S6809 warns about is
    // harmless here (both overloads share identical transaction semantics).
    @SuppressWarnings("java:S6809")
    @Transactional(readOnly = true)
    public AppPageResponse<AppAuthorSummary> getAuthors(
            Integer page,
            Integer size,
            String sortBy,
            String sortDir,
            Long libraryId,
            String search,
            Boolean hasPhoto) {

        return getAuthors(page, size, sortBy, sortDir, libraryId, search, hasPhoto,
                null, null, null, null);
    }

    // Cohesive filter/paging query parameters intentionally passed positionally rather than bundled
    // into a holder, matching the mobile /authors endpoint contract and its call sites.
    @SuppressWarnings("java:S107")
    @Transactional(readOnly = true)
    public AppPageResponse<AppAuthorSummary> getAuthors(
            Integer page,
            Integer size,
            String sortBy,
            String sortDir,
            Long libraryId,
            String search,
            Boolean hasPhoto,
            String matchStatus,
            String readStatus,
            String bookCount,
            String genre) {

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);
        validateLibraryAccess(accessibleLibraryIds, libraryId);

        int pageNum = page != null && page >= 0 ? page : 0;
        int pageSize = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        Set<Long> photoIds = authorPhotoIndex.authorIdsWithPhoto();
        if (Boolean.TRUE.equals(hasPhoto) && photoIds.isEmpty()) {
            return AppPageResponse.of(Collections.emptyList(), pageNum, pageSize, 0L);
        }

        String fromClause = buildFromClause(genre);
        String whereClause = buildWhereClause(
                accessibleLibraryIds, libraryId, search, hasPhoto, photoIds, matchStatus, genre);
        String havingClause = buildHavingClause(readStatus, bookCount);

        long totalElements = countAuthors(
                fromClause, whereClause, havingClause, accessibleLibraryIds, libraryId,
                user.getId(), search, hasPhoto, photoIds, genre);

        if (totalElements == 0) {
            return AppPageResponse.of(Collections.emptyList(), pageNum, pageSize, 0L);
        }

        String orderClause = buildOrderClause(sortBy, sortDir, photoIds);
        String dataJpql = "SELECT a, " + BOOK_COUNT_EXPR + fromClause + whereClause
                + " GROUP BY a" + havingClause + orderClause;
        TypedQuery<Object[]> dataQuery = entityManager.createQuery(dataJpql, Object[].class);
        setQueryParams(dataQuery, accessibleLibraryIds, libraryId, user.getId(), search,
                hasPhoto, photoIds, genre, dataJpql.contains(PHOTO_IDS_PARAM));
        dataQuery.setFirstResult(pageNum * pageSize);
        dataQuery.setMaxResults(pageSize);

        List<Object[]> results = dataQuery.getResultList();

        List<AppAuthorSummary> summaries = results.stream()
                .map(row -> {
                    AuthorEntity author = (AuthorEntity) row[0];
                    long accessibleBookCount = (Long) row[1];
                    boolean authorHasPhoto = authorPhotoIndex.hasPhoto(author.getId());
                    Long photoLastModified = authorHasPhoto ? getAuthorThumbnailLastModified(author.getId()) : null;
                    return AppAuthorSummary.builder()
                            .id(author.getId())
                            .name(author.getName())
                            .asin(author.getAsin())
                            .bookCount((int) accessibleBookCount)
                            .hasPhoto(authorHasPhoto)
                            .photoLastModified(photoLastModified)
                            .build();
                })
                .toList();

        return AppPageResponse.of(summaries, pageNum, pageSize, totalElements);
    }

    // MariaDB FORCE INDEX is required for the category-first semijoin; all dynamic values remain
    // bound parameters.
    @Transactional(readOnly = true)
    @SuppressWarnings("java:S2077")
    public List<String> getCategories(Long libraryId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);
        validateLibraryAccess(accessibleLibraryIds, libraryId);

        StringBuilder sql = new StringBuilder(
                "SELECT c.name FROM category c WHERE EXISTS ("
                        + "SELECT 1 FROM book_metadata_category_mapping cm "
                        + "FORCE INDEX (fk_book_metadata_category_mapping_category) "
                        + "JOIN book b ON b.id = cm.book_id "
                        + "WHERE cm.category_id = c.id "
                        + "AND (b.deleted IS NULL OR b.deleted = false) "
                        + "AND (b.has_files = true OR b.is_physical = true) "
                        + "AND EXISTS (SELECT 1 FROM book_metadata_author_mapping am WHERE am.book_id = b.id)");
        appendNativeLibraryFilter(sql, accessibleLibraryIds, libraryId);
        sql.append(" LIMIT 1) ORDER BY c.name");

        Query query = entityManager.createNativeQuery(sql.toString(), String.class);
        setNativeLibraryParams(query, accessibleLibraryIds, libraryId);
        return query.getResultList();
    }

    private void appendNativeLibraryFilter(StringBuilder sql, Set<Long> accessibleLibraryIds, Long libraryId) {
        if (libraryId != null) {
            sql.append(" AND b.library_id = :libraryId");
        } else if (accessibleLibraryIds != null) {
            sql.append(" AND b.library_id IN (:libraryIds)");
        }
    }

    private void setNativeLibraryParams(Query query, Set<Long> accessibleLibraryIds, Long libraryId) {
        if (libraryId != null) {
            query.setParameter("libraryId", libraryId);
        } else if (accessibleLibraryIds != null) {
            query.setParameter("libraryIds", accessibleLibraryIds);
        }
    }

    @Transactional(readOnly = true)
    public AppAuthorDetail getAuthorDetail(Long authorId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(authorId));

        // Verify access for non-admin users
        if (accessibleLibraryIds != null
                && (accessibleLibraryIds.isEmpty() || !authorRepository.existsByIdAndLibraryIds(authorId, accessibleLibraryIds))) {
            throw ApiError.AUTHOR_NOT_FOUND.createException(authorId);
        }

        // Count books accessible to this user
        int bookCount = countAccessibleBooks(authorId, accessibleLibraryIds);
        boolean authorHasPhoto = authorPhotoIndex.hasPhoto(author.getId());
        Long photoLastModified = authorHasPhoto ? getAuthorThumbnailLastModified(author.getId()) : null;

        return AppAuthorDetail.builder()
                .id(author.getId())
                .name(author.getName())
                .description(author.getDescription())
                .asin(author.getAsin())
                .bookCount(bookCount)
                .hasPhoto(authorHasPhoto)
                .photoLastModified(photoLastModified)
                .build();
    }

    private int countAccessibleBooks(Long authorId, Set<Long> accessibleLibraryIds) {
        StringBuilder jpql = new StringBuilder(
                "SELECT COUNT(DISTINCT bm.id) FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b"
                        + " WHERE a.id = :authorId AND (b.deleted IS NULL OR b.deleted = false)"
                        + " AND (b.hasFiles = true OR b.isPhysical = true)");
        if (accessibleLibraryIds != null) {
            jpql.append(" AND b.library.id IN :libraryIds");
        }
        TypedQuery<Long> query = entityManager.createQuery(jpql.toString(), Long.class);
        query.setParameter("authorId", authorId);
        if (accessibleLibraryIds != null) {
            query.setParameter("libraryIds", accessibleLibraryIds);
        }
        return query.getSingleResult().intValue();
    }

    private String buildFromClause(String genre) {
        StringBuilder fromClause = new StringBuilder(
                " FROM AuthorEntity a JOIN a.bookMetadataEntityList m JOIN m.book b"
                        + " LEFT JOIN b.userBookProgress p ON p.user.id = :userId");
        if (hasText(genre)) {
            fromClause.append(" JOIN m.categories category");
        }
        return fromClause.toString();
    }

    private String buildWhereClause(
            Set<Long> accessibleLibraryIds,
            Long libraryId,
            String search,
            Boolean hasPhoto,
            Set<Long> photoIds,
            String matchStatus,
            String genre) {

        StringBuilder whereClause = new StringBuilder(
                " WHERE (b.deleted IS NULL OR b.deleted = false)"
                        + " AND (b.hasFiles = true OR b.isPhysical = true)");
        appendLibraryFilter(whereClause, accessibleLibraryIds, libraryId);
        if (hasText(search)) {
            whereClause.append(" AND LOWER(a.name) LIKE :search");
        }
        if (hasText(genre)) {
            whereClause.append(" AND LOWER(category.name) = :genre");
        }
        if ("matched".equalsIgnoreCase(matchStatus)) {
            whereClause.append(" AND a.asin IS NOT NULL AND a.asin <> ''");
        } else if ("unmatched".equalsIgnoreCase(matchStatus)) {
            whereClause.append(" AND (a.asin IS NULL OR a.asin = '')");
        }
        if (hasPhoto != null && !photoIds.isEmpty()) {
            whereClause.append(Boolean.TRUE.equals(hasPhoto)
                    ? " AND a.id IN :photoIds"
                    : " AND a.id NOT IN :photoIds");
        }
        return whereClause.toString();
    }

    private String buildHavingClause(String readStatus, String bookCount) {
        List<String> conditions = new ArrayList<>();
        String normalizedReadStatus = normalize(readStatus);
        switch (normalizedReadStatus) {
            case "all-read" -> conditions.add(READ_COUNT_EXPR + " = " + BOOK_COUNT_EXPR);
            case "in-progress" -> {
                conditions.add(READ_COUNT_EXPR + " <> " + BOOK_COUNT_EXPR);
                conditions.add(IN_PROGRESS_COUNT_EXPR + " > 0");
            }
            case "some-read" -> {
                conditions.add(READ_COUNT_EXPR + " > 0");
                conditions.add(READ_COUNT_EXPR + " <> " + BOOK_COUNT_EXPR);
                conditions.add(IN_PROGRESS_COUNT_EXPR + " = 0");
            }
            case "unread" -> {
                conditions.add(READ_COUNT_EXPR + " = 0");
                conditions.add(IN_PROGRESS_COUNT_EXPR + " = 0");
            }
            default -> {
                // No read-status filter.
            }
        }

        String normalizedBookCount = normalize(bookCount);
        switch (normalizedBookCount) {
            case "0", "1", "2", "3", "4", "5" -> conditions.add(BOOK_COUNT_EXPR + " = " + normalizedBookCount);
            case "6-10" -> conditions.add(BOOK_COUNT_EXPR + " BETWEEN 6 AND 10");
            case "11-20" -> conditions.add(BOOK_COUNT_EXPR + " BETWEEN 11 AND 20");
            case "21-35" -> conditions.add(BOOK_COUNT_EXPR + " BETWEEN 21 AND 35");
            case "36+" -> conditions.add(BOOK_COUNT_EXPR + " >= 36");
            default -> {
                // No book-count filter.
            }
        }
        return conditions.isEmpty() ? "" : " HAVING " + String.join(" AND ", conditions);
    }

    // S107: cohesive query fragments + bound-parameter inputs, kept positional to mirror the caller.
    // S2077: the JPQL is assembled from internally-built clause fragments only; user-supplied values
    // are bound via setQueryParams as named parameters, never concatenated into the query text.
    @SuppressWarnings({"java:S107", "java:S2077"})
    private long countAuthors(
            String fromClause,
            String whereClause,
            String havingClause,
            Set<Long> accessibleLibraryIds,
            Long libraryId,
            Long userId,
            String search,
            Boolean hasPhoto,
            Set<Long> photoIds,
            String genre) {

        if (havingClause.isEmpty()) {
            String countJpql = "SELECT COUNT(DISTINCT a.id)" + fromClause + whereClause;
            TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
            setQueryParams(countQuery, accessibleLibraryIds, libraryId, userId, search,
                    hasPhoto, photoIds, genre, countJpql.contains(PHOTO_IDS_PARAM));
            return countQuery.getSingleResult();
        }

        String groupedCountJpql = "SELECT a.id" + fromClause + whereClause
                + " GROUP BY a.id" + havingClause;
        TypedQuery<Long> groupedCountQuery = entityManager.createQuery(groupedCountJpql, Long.class);
        setQueryParams(groupedCountQuery, accessibleLibraryIds, libraryId, userId, search,
                hasPhoto, photoIds, genre, groupedCountJpql.contains(PHOTO_IDS_PARAM));
        return groupedCountQuery.getResultList().size();
    }

    private void appendLibraryFilter(StringBuilder whereClause, Set<Long> accessibleLibraryIds, Long libraryId) {
        if (libraryId != null) {
            whereClause.append(" AND b.library.id = :libraryId");
        } else if (accessibleLibraryIds != null) {
            whereClause.append(" AND b.library.id IN :libraryIds");
        }
    }

    private void validateLibraryAccess(Set<Long> accessibleLibraryIds, Long libraryId) {
        if (libraryId != null && accessibleLibraryIds != null && !accessibleLibraryIds.contains(libraryId)) {
            throw ApiError.FORBIDDEN.createException("Access denied to library " + libraryId);
        }
    }

    private String buildOrderClause(String sortBy, String sortDir, Set<Long> photoIds) {
        String direction = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String nullsClause = "ASC".equals(direction) ? " NULLS LAST" : " NULLS FIRST";
        String field = switch (sortBy != null ? sortBy.toLowerCase() : "") {
            case "name" -> COL_A_NAME;
            case "bookcount", "book_count" -> BOOK_COUNT_EXPR;
            case "matched" -> "CASE WHEN a.asin IS NULL OR a.asin = '' THEN 0 ELSE 1 END";
            case "recentlyadded", "recently-added" -> "MAX(b.addedOn)";
            case "lastreadtime", "recently-read" -> "MAX(p.lastReadTime)";
            case "readprogress", "reading-progress" -> "(" + READ_COUNT_EXPR + " * 1.0 / " + BOOK_COUNT_EXPR + ")";
            case "avgrating", "avg-rating" -> "AVG(p.personalRating)";
            case "photo" -> photoIds.isEmpty() ? COL_A_NAME : "CASE WHEN a.id IN :photoIds THEN 1 ELSE 0 END";
            case "seriescount", "series-count" -> "COUNT(DISTINCT m.seriesName)";
            case "recent", "id" -> "a.id";
            default -> COL_A_NAME;
        };
        boolean nullableSort = field.startsWith("MAX(") || field.startsWith("AVG(");
        return " ORDER BY " + field + " " + direction + (nullableSort ? nullsClause : "")
                + ", a.name ASC, a.id ASC";
    }

    // Cohesive set of bound-parameter inputs mirroring the query builders; kept positional by design.
    @SuppressWarnings("java:S107")
    private void setQueryParams(
            TypedQuery<?> query,
            Set<Long> accessibleLibraryIds,
            Long libraryId,
            Long userId,
            String search,
            Boolean hasPhoto,
            Set<Long> photoIds,
            String genre,
            boolean usesPhotoIds) {

        query.setParameter(PARAM_USER_ID, userId);
        setLibraryParams(query, accessibleLibraryIds, libraryId);
        if (hasText(search)) {
            query.setParameter("search", "%" + search.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (hasText(genre)) {
            query.setParameter("genre", genre.trim().toLowerCase(Locale.ROOT));
        }
        if (usesPhotoIds && (hasPhoto != null || !photoIds.isEmpty())) {
            query.setParameter("photoIds", photoIds);
        }
    }

    private void setLibraryParams(TypedQuery<?> query, Set<Long> accessibleLibraryIds, Long libraryId) {
        if (libraryId != null) {
            query.setParameter("libraryId", libraryId);
        } else if (accessibleLibraryIds != null) {
            query.setParameter("libraryIds", accessibleLibraryIds);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

    private Long getAuthorThumbnailLastModified(Long authorId) {
        String thumbnailPath = fileService.getAuthorThumbnailFile(authorId);
        if (thumbnailPath == null || thumbnailPath.isBlank()) {
            return null;
        }
        Path path = Paths.get(thumbnailPath);
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : null;
        } catch (IOException _) {
            return null;
        }
    }
}
