package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.model.enums.ContentRestrictionMode;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.model.enums.OpdsSortOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.*;
import java.util.stream.Collectors;

public class BookOpdsRepositoryImpl implements BookOpdsRepositoryCustom {

    private static final String DELETED_FILTER = "(b.deleted IS NULL OR b.deleted = false)";
    private static final String RATING_COUNT = """
            (CASE WHEN m.hardcoverRating IS NOT NULL AND m.hardcoverRating > 0 THEN 1 ELSE 0 END
            + CASE WHEN m.amazonRating IS NOT NULL AND m.amazonRating > 0 THEN 1 ELSE 0 END
            + CASE WHEN m.goodreadsRating IS NOT NULL AND m.goodreadsRating > 0 THEN 1 ELSE 0 END)
            """;
    private static final String RATING_SUM = "(COALESCE(m.hardcoverRating, 0.0) + COALESCE(m.amazonRating, 0.0) + COALESCE(m.goodreadsRating, 0.0))";
    private static final String RATING_SCORE = "(CASE WHEN " + RATING_COUNT + " = 0 THEN 0.0 ELSE " + RATING_SUM + " / " + RATING_COUNT + " END)";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<Long> findBookIds(OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        return findIds("", "", "", Map.of(), sortOrder, pageable, restrictions);
    }

    @Override
    public Page<Long> findRecentBookIds(OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        return findBookIds(sortOrder, pageable, restrictions);
    }

    @Override
    public Page<Long> findBookIdsByLibraryIds(Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        if (isEmpty(libraryIds)) return Page.empty(pageable);
        return findIds("", "", "b.library.id IN :libraryIds", Map.of("libraryIds", libraryIds), sortOrder, pageable, restrictions);
    }

    @Override
    public Page<Long> findRecentBookIdsByLibraryIds(Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        return findBookIdsByLibraryIds(libraryIds, sortOrder, pageable, restrictions);
    }

    @Override
    public Page<Long> findBookIdsByShelfId(Long shelfId, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        return findIds(
                "JOIN b.shelves shelfFilter",
                "JOIN b.shelves shelfFilter",
                "shelfFilter.id = :shelfId",
                Map.of("shelfId", shelfId),
                sortOrder,
                pageable,
                restrictions);
    }

    @Override
    public Page<Long> findBookIdsByMetadataSearch(String text, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        return findIds("", "", metadataSearchClause(), Map.of("text", text), sortOrder, pageable, restrictions);
    }

    @Override
    public Page<Long> findBookIdsByMetadataSearchAndLibraryIds(String text, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        if (isEmpty(libraryIds)) return Page.empty(pageable);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("text", text);
        params.put("libraryIds", libraryIds);
        return findIds("", "", "b.library.id IN :libraryIds AND " + metadataSearchClause(), params, sortOrder, pageable, restrictions);
    }

    @Override
    public Page<Long> findBookIdsByMetadataSearchAndShelfIds(String text, Collection<Long> libraryIds, Collection<Long> shelfIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        if (isEmpty(libraryIds) || isEmpty(shelfIds)) return Page.empty(pageable);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("text", text);
        params.put("libraryIds", libraryIds);
        params.put("shelfIds", shelfIds);
        return findIds(
                "JOIN b.shelves shelfFilter",
                "JOIN b.shelves shelfFilter",
                "b.library.id IN :libraryIds AND shelfFilter.id IN :shelfIds AND " + metadataSearchClause(),
                params,
                sortOrder,
                pageable,
                restrictions);
    }

    @Override
    public Page<Long> findBookIdsByShelfIds(Collection<Long> libraryIds, Collection<Long> shelfIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        if (isEmpty(libraryIds) || isEmpty(shelfIds)) return Page.empty(pageable);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("libraryIds", libraryIds);
        params.put("shelfIds", shelfIds);
        return findIds(
                "JOIN b.shelves shelfFilter",
                "JOIN b.shelves shelfFilter",
                "b.library.id IN :libraryIds AND shelfFilter.id IN :shelfIds",
                params,
                sortOrder,
                pageable,
                restrictions);
    }

    @Override
    public Page<Long> findBookIdsByAuthorName(String authorName, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        return findIds(
                "JOIN m.authors authorFilter",
                "JOIN m.authors authorFilter",
                "authorFilter.name = :authorName",
                Map.of("authorName", authorName),
                sortOrder,
                pageable,
                restrictions);
    }

    @Override
    public Page<Long> findBookIdsByAuthorNameAndLibraryIds(String authorName, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        if (isEmpty(libraryIds)) return Page.empty(pageable);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("authorName", authorName);
        params.put("libraryIds", libraryIds);
        return findIds(
                "JOIN m.authors authorFilter",
                "JOIN m.authors authorFilter",
                "authorFilter.name = :authorName AND b.library.id IN :libraryIds",
                params,
                sortOrder,
                pageable,
                restrictions);
    }

    @Override
    public Page<Long> findBookIdsBySeriesName(String seriesName, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        return findIds("", "", "m.seriesName = :seriesName", Map.of("seriesName", seriesName), sortOrder, pageable, restrictions);
    }

    @Override
    public Page<Long> findBookIdsBySeriesNameAndLibraryIds(String seriesName, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        if (isEmpty(libraryIds)) return Page.empty(pageable);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("seriesName", seriesName);
        params.put("libraryIds", libraryIds);
        return findIds("", "", "m.seriesName = :seriesName AND b.library.id IN :libraryIds", params, sortOrder, pageable, restrictions);
    }

    @Override
    public List<Long> findRandomBookIdsByLibraryIds(Collection<Long> libraryIds, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        if (isEmpty(libraryIds)) return List.of();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("libraryIds", libraryIds);
        String where = buildWhere(combineWhere("b.library.id IN :libraryIds", restrictionClause(restrictions, params)));
        String jpql = """
                SELECT b.id
                FROM BookEntity b
                LEFT JOIN b.metadata m
                %s
                ORDER BY function('RAND')
                """.formatted(where);
        TypedQuery<Long> query = entityManager.createQuery(jpql, Long.class);
        bindParams(query, params);
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());
        return query.getResultList();
    }

    private Page<Long> findIds(String selectJoins, String countJoins, String extraWhere, Map<String, ?> params, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions) {
        Map<String, Object> queryParams = new LinkedHashMap<>(params);
        String where = buildWhere(combineWhere(extraWhere, restrictionClause(restrictions, queryParams)));
        String selectJpql = """
                SELECT b.id
                FROM BookEntity b
                LEFT JOIN b.metadata m
                LEFT JOIN m.authors sortAuthor
                %s
                %s
                GROUP BY b.id, b.addedOn, m.title, m.seriesName, m.seriesNumber, m.hardcoverRating, m.amazonRating, m.goodreadsRating
                %s
                """.formatted(selectJoins, where, orderBy(sortOrder));

        TypedQuery<Long> query = entityManager.createQuery(selectJpql, Long.class);
        bindParams(query, queryParams);
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());
        List<Long> ids = query.getResultList();

        String countJpql = """
                SELECT COUNT(DISTINCT b.id)
                FROM BookEntity b
                LEFT JOIN b.metadata m
                %s
                %s
                """.formatted(countJoins, where);
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        bindParams(countQuery, queryParams);
        Long total = countQuery.getSingleResult();

        return new PageImpl<>(ids, pageable, total);
    }

    private String combineWhere(String left, String right) {
        List<String> clauses = new ArrayList<>();
        if (left != null && !left.isBlank()) clauses.add(left);
        if (right != null && !right.isBlank()) clauses.add(right);
        return String.join(" AND ", clauses);
    }

    private String buildWhere(String extraWhere) {
        if (extraWhere == null || extraWhere.isBlank()) {
            return "WHERE " + DELETED_FILTER;
        }
        return "WHERE " + DELETED_FILTER + " AND " + extraWhere;
    }

    private String metadataSearchClause() {
        return "m.searchText LIKE CONCAT('%', :text, '%')";
    }

    private String restrictionClause(Collection<UserContentRestrictionEntity> restrictions, Map<String, Object> params) {
        if (isEmpty(restrictions)) return "";

        List<String> clauses = new ArrayList<>();
        addCollectionRestriction(clauses, params, restrictions, ContentRestrictionType.CATEGORY, ContentRestrictionMode.EXCLUDE, "categories", "excludedCategories", false);
        addCollectionRestriction(clauses, params, restrictions, ContentRestrictionType.CATEGORY, ContentRestrictionMode.ALLOW_ONLY, "categories", "allowedCategories", true);
        addCollectionRestriction(clauses, params, restrictions, ContentRestrictionType.TAG, ContentRestrictionMode.EXCLUDE, "tags", "excludedTags", false);
        addCollectionRestriction(clauses, params, restrictions, ContentRestrictionType.TAG, ContentRestrictionMode.ALLOW_ONLY, "tags", "allowedTags", true);
        addCollectionRestriction(clauses, params, restrictions, ContentRestrictionType.MOOD, ContentRestrictionMode.EXCLUDE, "moods", "excludedMoods", false);
        addCollectionRestriction(clauses, params, restrictions, ContentRestrictionType.MOOD, ContentRestrictionMode.ALLOW_ONLY, "moods", "allowedMoods", true);
        addScalarRestriction(clauses, params, restrictions, ContentRestrictionType.CONTENT_RATING, ContentRestrictionMode.EXCLUDE, "contentRating", "excludedContentRatings", false);
        addScalarRestriction(clauses, params, restrictions, ContentRestrictionType.CONTENT_RATING, ContentRestrictionMode.ALLOW_ONLY, "contentRating", "allowedContentRatings", true);

        Integer maxAgeRating = maxAgeRating(restrictions);
        if (maxAgeRating != null) {
            params.put("maxAgeRating", maxAgeRating);
            clauses.add("NOT EXISTS (SELECT 1 FROM BookMetadataEntity ageMeta WHERE ageMeta.bookId = b.id AND ageMeta.ageRating >= :maxAgeRating)");
        }

        return String.join(" AND ", clauses);
    }

    private void addCollectionRestriction(List<String> clauses, Map<String, Object> params, Collection<UserContentRestrictionEntity> restrictions,
                                          ContentRestrictionType type, ContentRestrictionMode mode, String attribute, String paramName, boolean required) {
        Set<String> values = values(restrictions, type, mode);
        if (values.isEmpty()) return;
        params.put(paramName, values);
        String metadataAlias = "meta" + paramName;
        String valueAlias = "value" + paramName;
        String exists = "EXISTS (SELECT 1 FROM BookMetadataEntity " + metadataAlias + " JOIN " + metadataAlias + "." + attribute + " " + valueAlias
                + " WHERE " + metadataAlias + ".bookId = b.id AND LOWER(" + valueAlias + ".name) IN :" + paramName + ")";
        clauses.add(required ? exists : "NOT " + exists);
    }

    private void addScalarRestriction(List<String> clauses, Map<String, Object> params, Collection<UserContentRestrictionEntity> restrictions,
                                      ContentRestrictionType type, ContentRestrictionMode mode, String attribute, String paramName, boolean required) {
        Set<String> values = values(restrictions, type, mode);
        if (values.isEmpty()) return;
        params.put(paramName, values);
        String metadataAlias = "meta" + paramName;
        String exists = "EXISTS (SELECT 1 FROM BookMetadataEntity " + metadataAlias
                + " WHERE " + metadataAlias + ".bookId = b.id AND LOWER(" + metadataAlias + "." + attribute + ") IN :" + paramName + ")";
        clauses.add(required ? exists : "NOT " + exists);
    }

    private Set<String> values(Collection<UserContentRestrictionEntity> restrictions, ContentRestrictionType type, ContentRestrictionMode mode) {
        return restrictions.stream()
                .filter(r -> r.getRestrictionType() == type && r.getMode() == mode)
                .map(r -> r.getValue().toLowerCase())
                .collect(Collectors.toSet());
    }

    private Integer maxAgeRating(Collection<UserContentRestrictionEntity> restrictions) {
        return restrictions.stream()
                .filter(r -> r.getRestrictionType() == ContentRestrictionType.AGE_RATING)
                .filter(r -> r.getMode() == ContentRestrictionMode.EXCLUDE)
                .map(r -> {
                    try {
                        return Integer.parseInt(r.getValue());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);
    }

    private String orderBy(OpdsSortOrder sortOrder) {
        return switch (sortOrder == null ? OpdsSortOrder.RECENT : sortOrder) {
            case RECENT -> "ORDER BY b.addedOn DESC, b.id DESC";
            case TITLE_ASC -> "ORDER BY LOWER(COALESCE(m.title, '')) ASC, b.addedOn DESC, b.id DESC";
            case TITLE_DESC -> "ORDER BY LOWER(COALESCE(m.title, '')) DESC, b.addedOn DESC, b.id DESC";
            case AUTHOR_ASC -> "ORDER BY LOWER(COALESCE(MIN(sortAuthor.name), '')) ASC, b.addedOn DESC, b.id DESC";
            case AUTHOR_DESC -> "ORDER BY LOWER(COALESCE(MIN(sortAuthor.name), '')) DESC, b.addedOn DESC, b.id DESC";
            case SERIES_ASC -> "ORDER BY CASE WHEN m.seriesName IS NULL OR m.seriesName = '' THEN 1 ELSE 0 END ASC, LOWER(COALESCE(m.seriesName, '')) ASC, COALESCE(m.seriesNumber, 999999) ASC, b.addedOn DESC, b.id DESC";
            case SERIES_DESC -> "ORDER BY CASE WHEN m.seriesName IS NULL OR m.seriesName = '' THEN 1 ELSE 0 END ASC, LOWER(COALESCE(m.seriesName, '')) DESC, COALESCE(m.seriesNumber, 999999) DESC, b.addedOn DESC, b.id DESC";
            case RATING_ASC -> "ORDER BY CASE WHEN " + RATING_COUNT + " = 0 THEN 1 ELSE 0 END ASC, " + RATING_SCORE + " ASC, b.addedOn DESC, b.id DESC";
            case RATING_DESC -> "ORDER BY CASE WHEN " + RATING_COUNT + " = 0 THEN 1 ELSE 0 END ASC, " + RATING_SCORE + " DESC, b.addedOn DESC, b.id DESC";
        };
    }

    private void bindParams(TypedQuery<?> query, Map<String, ?> params) {
        params.forEach(query::setParameter);
    }

    private boolean isEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }
}
