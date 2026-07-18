package org.booklore.app.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.model.enums.ContentRestrictionMode;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.repository.UserContentRestrictionRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppContentRestrictionQueryService {

    private final UserContentRestrictionRepository restrictionRepository;

    private final Cache<Long, RestrictionQueryScope> restrictionQueryScopeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(100)
            .build();

    public RestrictionQueryScope scopeForUser(Long userId) {
        return restrictionQueryScopeCache.get(userId, ignored -> {
            List<UserContentRestrictionEntity> restrictions = restrictionRepository.findByUserId(userId);
            StringBuilder clause = new StringBuilder();
            Map<String, Object> parameters = new HashMap<>();

            appendCollectionRestriction(clause, parameters, restrictions,
                    ContentRestrictionType.CATEGORY, "categories", "Categories");
            appendCollectionRestriction(clause, parameters, restrictions,
                    ContentRestrictionType.TAG, "tags", "Tags");
            appendCollectionRestriction(clause, parameters, restrictions,
                    ContentRestrictionType.MOOD, "moods", "Moods");
            appendScalarRestriction(clause, parameters, restrictions,
                    ContentRestrictionType.CONTENT_RATING, "b.metadata.contentRating", "ContentRatings");

            Integer maxAgeRating = restrictions.stream()
                    .filter(restriction -> restriction.getRestrictionType() == ContentRestrictionType.AGE_RATING)
                    .filter(restriction -> restriction.getMode() == ContentRestrictionMode.EXCLUDE)
                    .map(UserContentRestrictionEntity::getValue)
                    .map(value -> {
                        try {
                            return Integer.parseInt(value);
                        } catch (NumberFormatException ignoredException) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .min(Integer::compareTo)
                    .orElse(null);
            if (maxAgeRating != null) {
                clause.append(" AND (b.metadata.ageRating IS NULL OR b.metadata.ageRating < :restrictionMaxAgeRating)");
                parameters.put("restrictionMaxAgeRating", maxAgeRating);
            }
            return new RestrictionQueryScope(clause.toString(), Map.copyOf(parameters));
        });
    }

    private void appendCollectionRestriction(
            StringBuilder clause,
            Map<String, Object> parameters,
            List<UserContentRestrictionEntity> restrictions,
            ContentRestrictionType type,
            String attribute,
            String parameterSuffix) {
        Set<String> excluded = restrictionValues(restrictions, type, ContentRestrictionMode.EXCLUDE);
        if (!excluded.isEmpty()) {
            String parameter = "restrictionExcluded" + parameterSuffix;
            clause.append(" AND NOT EXISTS (SELECT restrictedMetadata.bookId FROM BookMetadataEntity restrictedMetadata")
                    .append(" JOIN restrictedMetadata.").append(attribute).append(" restrictedValue")
                    .append(" WHERE restrictedMetadata.bookId = b.id AND LOWER(restrictedValue.name) IN :")
                    .append(parameter).append(")");
            parameters.put(parameter, excluded);
        }
        Set<String> allowed = restrictionValues(restrictions, type, ContentRestrictionMode.ALLOW_ONLY);
        if (!allowed.isEmpty()) {
            String parameter = "restrictionAllowed" + parameterSuffix;
            clause.append(" AND EXISTS (SELECT restrictedMetadata.bookId FROM BookMetadataEntity restrictedMetadata")
                    .append(" JOIN restrictedMetadata.").append(attribute).append(" restrictedValue")
                    .append(" WHERE restrictedMetadata.bookId = b.id AND LOWER(restrictedValue.name) IN :")
                    .append(parameter).append(")");
            parameters.put(parameter, allowed);
        }
    }

    private void appendScalarRestriction(
            StringBuilder clause,
            Map<String, Object> parameters,
            List<UserContentRestrictionEntity> restrictions,
            ContentRestrictionType type,
            String attribute,
            String parameterSuffix) {
        Set<String> excluded = restrictionValues(restrictions, type, ContentRestrictionMode.EXCLUDE);
        if (!excluded.isEmpty()) {
            String parameter = "restrictionExcluded" + parameterSuffix;
            clause.append(" AND (").append(attribute).append(" IS NULL OR LOWER(")
                    .append(attribute).append(") NOT IN :").append(parameter).append(")");
            parameters.put(parameter, excluded);
        }
        Set<String> allowed = restrictionValues(restrictions, type, ContentRestrictionMode.ALLOW_ONLY);
        if (!allowed.isEmpty()) {
            String parameter = "restrictionAllowed" + parameterSuffix;
            clause.append(" AND LOWER(").append(attribute).append(") IN :").append(parameter);
            parameters.put(parameter, allowed);
        }
    }

    private Set<String> restrictionValues(
            List<UserContentRestrictionEntity> restrictions,
            ContentRestrictionType type,
            ContentRestrictionMode mode) {
        return restrictions.stream()
                .filter(restriction -> restriction.getRestrictionType() == type)
                .filter(restriction -> restriction.getMode() == mode)
                .map(UserContentRestrictionEntity::getValue)
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    public record RestrictionQueryScope(String clause, Map<String, Object> parameters) {
        public static RestrictionQueryScope empty() {
            return new RestrictionQueryScope("", Map.of());
        }
    }
}
