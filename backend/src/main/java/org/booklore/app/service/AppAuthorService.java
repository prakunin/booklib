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
import jakarta.persistence.TypedQuery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class AppAuthorService {

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 50;

    private final AuthorRepository authorRepository;
    private final AuthenticationService authenticationService;
    private final AuthorPhotoIndex authorPhotoIndex;
    private final EntityManager entityManager;
    private final FileService fileService;

    @Transactional(readOnly = true)
    public AppPageResponse<AppAuthorSummary> getAuthors(
            Integer page,
            Integer size,
            String sortBy,
            String sortDir,
            Long libraryId,
            String search,
            Boolean hasPhoto) {

        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        int pageNum = page != null && page >= 0 ? page : 0;
        int pageSize = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        StringBuilder whereClause = new StringBuilder(" WHERE (1=1)");
        buildLibraryFilter(whereClause, accessibleLibraryIds, libraryId);
        buildSearchFilter(whereClause, search);

        String fromClause = " FROM AuthorEntity a LEFT JOIN a.bookMetadataEntityList bm LEFT JOIN bm.book b";

        // Count query
        String countJpql = "SELECT COUNT(DISTINCT a.id)" + fromClause + whereClause;
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        setQueryParams(countQuery, accessibleLibraryIds, libraryId, search);
        long totalElements = countQuery.getSingleResult();

        if (totalElements == 0) {
            return AppPageResponse.of(Collections.emptyList(), pageNum, pageSize, 0L);
        }

        // Data query with book count
        String orderClause = buildOrderClause(sortBy, sortDir);
        String dataJpql = "SELECT a, COUNT(DISTINCT bm.id)" + fromClause + whereClause
                + " GROUP BY a" + orderClause;
        TypedQuery<Object[]> dataQuery = entityManager.createQuery(dataJpql, Object[].class);
        setQueryParams(dataQuery, accessibleLibraryIds, libraryId, search);
        dataQuery.setFirstResult(pageNum * pageSize);
        dataQuery.setMaxResults(pageSize);

        List<Object[]> results = dataQuery.getResultList();

        List<AppAuthorSummary> summaries = results.stream()
                .map(row -> {
                    AuthorEntity author = (AuthorEntity) row[0];
                    long bookCount = (Long) row[1];
                    boolean authorHasPhoto = authorPhotoIndex.hasPhoto(author.getId());
                    Long photoLastModified = authorHasPhoto ? getAuthorThumbnailLastModified(author.getId()) : null;
                    return AppAuthorSummary.builder()
                            .id(author.getId())
                            .name(author.getName())
                            .asin(author.getAsin())
                            .bookCount((int) bookCount)
                            .hasPhoto(authorHasPhoto)
                            .photoLastModified(photoLastModified)
                            .build();
                })
                .toList();

        // Post-filter by hasPhoto if requested
        if (hasPhoto != null) {
            summaries = summaries.stream()
                    .filter(s -> s.isHasPhoto() == hasPhoto)
                    .toList();
            // Adjust total count for hasPhoto filter — requires a separate count
            long filteredTotal = countAuthorsWithPhotoFilter(accessibleLibraryIds, libraryId, search, hasPhoto);
            return AppPageResponse.of(summaries, pageNum, pageSize, filteredTotal);
        }

        return AppPageResponse.of(summaries, pageNum, pageSize, totalElements);
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
                        + " AND b.hasFiles = true");
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

    private long countAuthorsWithPhotoFilter(Set<Long> accessibleLibraryIds, Long libraryId, String search, boolean hasPhoto) {
        // hasPhoto lives on disk, so intersect the matching author ids with the cached photo index
        // rather than loading every matching AuthorEntity and probing the filesystem per row.
        StringBuilder whereClause = new StringBuilder(" WHERE (1=1)");
        buildLibraryFilter(whereClause, accessibleLibraryIds, libraryId);
        buildSearchFilter(whereClause, search);

        String jpql = "SELECT DISTINCT a.id FROM AuthorEntity a LEFT JOIN a.bookMetadataEntityList bm LEFT JOIN bm.book b"
                + whereClause;
        TypedQuery<Long> query = entityManager.createQuery(jpql, Long.class);
        setQueryParams(query, accessibleLibraryIds, libraryId, search);

        Set<Long> authorsWithPhoto = authorPhotoIndex.authorIdsWithPhoto();
        return query.getResultList().stream()
                .filter(id -> authorsWithPhoto.contains(id) == hasPhoto)
                .count();
    }

    private void buildLibraryFilter(StringBuilder whereClause, Set<Long> accessibleLibraryIds, Long libraryId) {
        if (libraryId != null) {
            whereClause.append(" AND b.library.id = :libraryId");
        } else if (accessibleLibraryIds != null) {
            whereClause.append(" AND b.library.id IN :libraryIds");
        }
        whereClause.append(" AND (b.deleted IS NULL OR b.deleted = false)");
        whereClause.append(" AND b.hasFiles = true");
    }

    private void buildSearchFilter(StringBuilder whereClause, String search) {
        if (search != null && !search.trim().isEmpty()) {
            whereClause.append(" AND LOWER(a.name) LIKE :search");
        }
    }

    private String buildOrderClause(String sortBy, String sortDir) {
        String direction = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String field = switch (sortBy != null ? sortBy.toLowerCase() : "") {
            case "name" -> "a.name";
            case "bookcount", "book_count" -> "COUNT(DISTINCT bm.id)";
            case "recent", "id" -> "a.id";
            default -> "a.name";
        };
        return " ORDER BY " + field + " " + direction;
    }

    private void setQueryParams(TypedQuery<?> query, Set<Long> accessibleLibraryIds, Long libraryId, String search) {
        if (libraryId != null) {
            query.setParameter("libraryId", libraryId);
        } else if (accessibleLibraryIds != null) {
            query.setParameter("libraryIds", accessibleLibraryIds);
        }
        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("search", "%" + search.trim().toLowerCase() + "%");
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
