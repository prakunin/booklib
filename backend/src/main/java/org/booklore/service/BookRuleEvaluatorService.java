package org.booklore.service;

import jakarta.persistence.criteria.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.GroupRule;
import org.booklore.model.dto.Rule;
import org.booklore.model.dto.RuleField;
import org.booklore.model.dto.RuleOperator;
import org.booklore.model.entity.*;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.model.enums.ReadStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

@Slf4j
@Service
@AllArgsConstructor
public class BookRuleEvaluatorService {

    private static final String FIELD_USER_BOOK_PROGRESS = "userBookProgress";
    private static final String FIELD_READ_STATUS = "readStatus";
    private static final String FIELD_METADATA = "metadata";
    private static final String FIELD_SERIES_NAME = "seriesName";
    private static final String FIELD_SERIES_NUMBER = "seriesNumber";
    private static final String FIELD_SERIES_TOTAL = "seriesTotal";
    private static final String FIELD_PERSONAL_RATING = "personalRating";
    private static final String FIELD_AUTHORS = "authors";
    private static final String FIELD_CATEGORIES = "categories";
    private static final String FIELD_MOODS = "moods";
    private static final String FIELD_BOOK_FILES = "bookFiles";
    private static final String FUNCTION_FLOOR = "FLOOR";
    private static final String READ_STATUS_UNSET = "UNSET";

    private final ObjectMapper objectMapper;

    private static final Set<RuleField> COMPOSITE_FIELDS = Set.of(
            RuleField.SERIES_STATUS, RuleField.SERIES_GAPS, RuleField.SERIES_POSITION
    );

    public Specification<BookEntity> toSpecification(GroupRule groupRule, Long userId) {
        return (root, query, cb) -> {
            // JOINs on multi-valued associations (authors, tags, shelves, etc.) can
            // produce duplicate rows — use DISTINCT so pagination counts and page
            // content reflect unique BookEntity results.
            query.distinct(true);

            Join<BookEntity, UserBookProgressEntity> progressJoin = root.join(FIELD_USER_BOOK_PROGRESS, JoinType.LEFT);

            Predicate userPredicate = cb.or(
                    cb.isNull(progressJoin.get("user").get("id")),
                    cb.equal(progressJoin.get("user").get("id"), userId)
            );

            Predicate rulePredicate = buildPredicate(groupRule, query, cb, root, progressJoin, userId);

            return cb.and(userPredicate, rulePredicate);
        };
    }

    private Predicate buildPredicate(GroupRule group, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin, Long userId) {
        if (group.getRules() == null || group.getRules().isEmpty()) {
            return cb.conjunction();
        }

        List<Predicate> predicates = new ArrayList<>();

        for (Object ruleObj : group.getRules()) {
            if (ruleObj == null) continue;

            Map<String, Object> ruleMap = objectMapper.convertValue(ruleObj, new TypeReference<>() {
            });
            String type = (String) ruleMap.get("type");

            if ("group".equals(type)) {
                GroupRule subGroup = objectMapper.convertValue(ruleObj, GroupRule.class);
                predicates.add(buildPredicate(subGroup, query, cb, root, progressJoin, userId));
            } else {
                try {
                    Rule rule = objectMapper.convertValue(ruleObj, Rule.class);
                    Predicate rulePredicate = buildRulePredicate(rule, query, cb, root, progressJoin, userId);
                    if (rulePredicate != null) {
                        predicates.add(rulePredicate);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse rule: {}, error: {}", ruleObj, e.getMessage(), e);
                }
            }
        }

        if (predicates.isEmpty()) {
            return cb.conjunction();
        }

        return group.getJoin() == org.booklore.model.dto.JoinType.AND
                ? cb.and(predicates.toArray(Predicate[]::new))
                : cb.or(predicates.toArray(Predicate[]::new));
    }

    private Predicate buildRulePredicate(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin, Long userId) {
        if (rule.getField() == null || rule.getOperator() == null) return null;

        if (rule.getField() == RuleField.METADATA_PRESENCE) {
            return buildMetadataPresencePredicate(rule, query, cb, root, progressJoin);
        }

        if (COMPOSITE_FIELDS.contains(rule.getField())) {
            return buildCompositeFieldPredicate(rule, query, cb, root, progressJoin, userId);
        }

        return switch (rule.getOperator()) {
            case EQUALS -> buildEquals(rule, query, cb, root, progressJoin);
            case NOT_EQUALS -> buildNotEquals(rule, query, cb, root, progressJoin);
            case CONTAINS -> buildContains(rule, query, cb, root, progressJoin);
            case DOES_NOT_CONTAIN -> {
                Predicate notContains = cb.not(buildContains(rule, query, cb, root, progressJoin));
                if (rule.getField() == RuleField.READ_STATUS) {
                    yield cb.or(cb.isNull(progressJoin.get(FIELD_READ_STATUS)), notContains);
                }
                yield notContains;
            }
            case STARTS_WITH -> buildStartsWith(rule, query, cb, root, progressJoin);
            case ENDS_WITH -> buildEndsWith(rule, query, cb, root, progressJoin);
            case GREATER_THAN -> buildGreaterThan(rule, cb, root, progressJoin);
            case GREATER_THAN_EQUAL_TO -> buildGreaterThanEqual(rule, cb, root, progressJoin);
            case LESS_THAN -> buildLessThan(rule, cb, root, progressJoin);
            case LESS_THAN_EQUAL_TO -> buildLessThanEqual(rule, cb, root, progressJoin);
            case IN_BETWEEN -> buildInBetween(rule, cb, root, progressJoin);
            case IS_EMPTY -> buildIsEmpty(rule, query, cb, root, progressJoin);
            case IS_NOT_EMPTY -> cb.not(buildIsEmpty(rule, query, cb, root, progressJoin));
            case INCLUDES_ANY -> buildIncludesAny(rule, query, cb, root, progressJoin);
            case EXCLUDES_ALL -> buildExcludesAll(rule, query, cb, root, progressJoin);
            case INCLUDES_ALL -> buildIncludesAll(rule, query, cb, root, progressJoin);
            case WITHIN_LAST -> buildWithinLast(rule, cb, root, progressJoin);
            case OLDER_THAN -> buildOlderThan(rule, cb, root, progressJoin);
            case THIS_PERIOD -> buildThisPeriod(rule, cb, root, progressJoin);
        };
    }

    private Predicate buildWithinLast(Rule rule, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        Expression<?> field = getFieldExpression(rule.getField(), cb, root, progressJoin);
        if (field == null) return cb.conjunction();

        Instant threshold = computeRelativeDateThreshold(rule);
        if (threshold == null) return cb.conjunction();

        if (rule.getField() == RuleField.PUBLISHED_DATE) {
            LocalDate ld = threshold.atZone(ZoneId.systemDefault()).toLocalDate();
            return cb.greaterThanOrEqualTo(field.as(LocalDate.class), ld);
        }
        return cb.greaterThanOrEqualTo(field.as(Instant.class), threshold);
    }

    private Predicate buildOlderThan(Rule rule, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        Expression<?> field = getFieldExpression(rule.getField(), cb, root, progressJoin);
        if (field == null) return cb.conjunction();

        Instant threshold = computeRelativeDateThreshold(rule);
        if (threshold == null) return cb.conjunction();

        if (rule.getField() == RuleField.PUBLISHED_DATE) {
            LocalDate ld = threshold.atZone(ZoneId.systemDefault()).toLocalDate();
            return cb.lessThan(field.as(LocalDate.class), ld);
        }
        return cb.lessThan(field.as(Instant.class), threshold);
    }

    private Predicate buildThisPeriod(Rule rule, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        Expression<?> field = getFieldExpression(rule.getField(), cb, root, progressJoin);
        if (field == null) return cb.conjunction();

        String period = rule.getValue() != null ? rule.getValue().toString().toLowerCase() : "year";
        LocalDate now = LocalDate.now();
        LocalDate start = switch (period) {
            case "week" -> now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> now.withDayOfMonth(1);
            default -> now.withDayOfYear(1);
        };

        Instant startInstant = start.atStartOfDay(ZoneId.systemDefault()).toInstant();

        if (rule.getField() == RuleField.PUBLISHED_DATE) {
            return cb.greaterThanOrEqualTo(field.as(LocalDate.class), start);
        }
        return cb.greaterThanOrEqualTo(field.as(Instant.class), startInstant);
    }

    private Instant computeRelativeDateThreshold(Rule rule) {
        if (rule.getValue() == null) return null;
        int amount;
        try {
            amount = ((Number) rule.getValue()).intValue();
        } catch (ClassCastException _) {
            try {
                amount = Integer.parseInt(rule.getValue().toString());
            } catch (NumberFormatException _) {
                return null;
            }
        }
        String unit = rule.getValueEnd() != null ? rule.getValueEnd().toString().toLowerCase() : "days";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = switch (unit) {
            case "weeks" -> now.minusWeeks(amount);
            case "months" -> now.minusMonths(amount);
            case "years" -> now.minusYears(amount);
            default -> now.minusDays(amount);
        };
        return threshold.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Predicate buildCompositeFieldPredicate(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin, Long userId) {
        boolean negate = rule.getOperator() == RuleOperator.NOT_EQUALS;
        String value = rule.getValue() != null ? rule.getValue().toString().toLowerCase() : "";
        Predicate hasSeries = cb.and(
                cb.isNotNull(root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.notEqual(cb.trim(root.get(FIELD_METADATA).get(FIELD_SERIES_NAME).as(String.class)), "")
        );

        Predicate result = switch (rule.getField()) {
            case SERIES_STATUS -> buildSeriesStatusPredicate(value, query, cb, root, hasSeries, userId);
            case SERIES_GAPS -> buildSeriesGapsPredicate(value, query, cb, root, hasSeries);
            case SERIES_POSITION -> buildSeriesPositionPredicate(value, query, cb, root, progressJoin, hasSeries, userId);
            default -> cb.conjunction();
        };

        return negate ? cb.not(result) : result;
    }

    private Predicate buildMetadataPresencePredicate(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        boolean hasOperator = rule.getOperator() == RuleOperator.EQUALS;
        String metadataField = rule.getValue() != null ? rule.getValue().toString() : "";
        Predicate isPresent = buildFieldPresencePredicate(metadataField, query, cb, root, progressJoin);
        return hasOperator ? isPresent : cb.not(isPresent);
    }

    private Predicate buildFieldPresencePredicate(String metadataField, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        return switch (metadataField) {
            // Cover image — stored as hash on BookEntity
            case "thumbnailUrl" -> cb.isNotNull(root.get("bookCoverHash"));

            // Personal rating — on progress join
            case FIELD_PERSONAL_RATING -> cb.isNotNull(progressJoin.get(FIELD_PERSONAL_RATING));

            // Audiobook duration — on BookFileEntity
            case "audiobookDuration" -> {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<BookFileEntity> subRoot = sub.from(BookFileEntity.class);
                sub.select(cb.literal(1L)).where(
                        cb.equal(subRoot.get("book").get("id"), root.get("id")),
                        cb.isNotNull(subRoot.get("durationSeconds"))
                );
                yield cb.exists(sub);
            }

            // Collection fields on BookMetadataEntity
            case FIELD_AUTHORS -> collectionPresence(query, cb, root, FIELD_AUTHORS);
            case FIELD_CATEGORIES -> collectionPresence(query, cb, root, FIELD_CATEGORIES);
            case FIELD_MOODS -> collectionPresence(query, cb, root, FIELD_MOODS);
            case "tags" -> collectionPresence(query, cb, root, "tags");

            // Comic collection fields
            case "comicCharacters" -> comicCollectionPresence(query, cb, root, "characters");
            case "comicTeams" -> comicCollectionPresence(query, cb, root, "teams");
            case "comicLocations" -> comicCollectionPresence(query, cb, root, "locations");

            // Comic creator role fields
            case "comicPencillers" -> comicCreatorPresence(query, cb, root, ComicCreatorRole.PENCILLER);
            case "comicInkers" -> comicCreatorPresence(query, cb, root, ComicCreatorRole.INKER);
            case "comicColorists" -> comicCreatorPresence(query, cb, root, ComicCreatorRole.COLORIST);
            case "comicLetterers" -> comicCreatorPresence(query, cb, root, ComicCreatorRole.LETTERER);
            case "comicCoverArtists" -> comicCreatorPresence(query, cb, root, ComicCreatorRole.COVER_ARTIST);
            case "comicEditors" -> comicCreatorPresence(query, cb, root, ComicCreatorRole.EDITOR);

            // String fields on BookMetadataEntity
            case "title", "subtitle", "description", "publisher", "language", FIELD_SERIES_NAME,
                 "isbn13", "isbn10", "asin", "contentRating", "narrator",
                 "goodreadsId", "hardcoverId", "googleId", "audibleId",
                 "lubimyczytacId", "ranobedbId", "comicvineId" ->
                    stringPresence(cb, root.get(FIELD_METADATA).get(metadataField));

            // Numeric/date/boolean fields on BookMetadataEntity
            case "pageCount", FIELD_SERIES_NUMBER, FIELD_SERIES_TOTAL, "ageRating", "publishedDate", "abridged",
                 "amazonRating", "goodreadsRating", "hardcoverRating", "ranobedbRating",
                 "lubimyczytacRating", "audibleRating",
                 "amazonReviewCount", "goodreadsReviewCount", "hardcoverReviewCount", "audibleReviewCount" ->
                    cb.isNotNull(root.get(FIELD_METADATA).get(metadataField));

            default -> cb.conjunction();
        };
    }

    private Predicate stringPresence(CriteriaBuilder cb, Expression<?> field) {
        return cb.and(cb.isNotNull(field), cb.notEqual(cb.trim(field.as(String.class)), ""));
    }

    private Predicate collectionPresence(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, String collectionName) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<BookEntity> subRoot = sub.from(BookEntity.class);
        Join<Object, Object> metadataJoin = subRoot.join(FIELD_METADATA, JoinType.INNER);
        metadataJoin.join(collectionName, JoinType.INNER);
        sub.select(cb.literal(1L)).where(cb.equal(subRoot.get("id"), root.get("id")));
        return cb.exists(sub);
    }

    private Predicate comicCollectionPresence(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, String collectionName) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<BookEntity> subRoot = sub.from(BookEntity.class);
        Join<Object, Object> metadataJoin = subRoot.join(FIELD_METADATA, JoinType.INNER);
        Join<Object, Object> comicJoin = metadataJoin.join("comicMetadata", JoinType.INNER);
        comicJoin.join(collectionName, JoinType.INNER);
        sub.select(cb.literal(1L)).where(cb.equal(subRoot.get("id"), root.get("id")));
        return cb.exists(sub);
    }

    private Predicate comicCreatorPresence(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, ComicCreatorRole role) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<ComicCreatorMappingEntity> subRoot = sub.from(ComicCreatorMappingEntity.class);
        sub.select(cb.literal(1L)).where(
                cb.equal(subRoot.get("comicMetadata").get("bookId"), root.get("id")),
                cb.equal(subRoot.get("role"), role)
        );
        return cb.exists(sub);
    }

    private Predicate buildSeriesStatusPredicate(String value, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Predicate hasSeries, Long userId) {
        Predicate condition = switch (value) {
            case "reading" -> seriesHasReadStatus(query, cb, root, userId, List.of("READING", "RE_READING"));
            case "not_started" -> cb.not(seriesHasReadStatus(query, cb, root, userId, List.of("READ", "READING", "RE_READING", "PARTIALLY_READ")));
            case "fully_read" -> seriesAllRead(query, cb, root, userId);
            case "completed" -> seriesOwnsLastBook(query, cb, root);
            case "ongoing" -> cb.and(seriesHasTotal(query, cb, root), cb.not(seriesOwnsLastBook(query, cb, root)));
            default -> cb.conjunction();
        };
        return cb.and(hasSeries, condition);
    }

    private Predicate seriesHasReadStatus(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Long userId, List<String> statuses) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<BookEntity> subRoot = sub.from(BookEntity.class);
        Join<Object, Object> subProgress = subRoot.join(FIELD_USER_BOOK_PROGRESS, JoinType.INNER);

        List<ReadStatus> readStatuses = statuses.stream()
                .map(ReadStatus::valueOf)
                .toList();

        sub.select(cb.literal(1L)).where(
                cb.equal(subRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.equal(subProgress.get("user").get("id"), userId),
                subProgress.get(FIELD_READ_STATUS).in(readStatuses)
        );
        return cb.exists(sub);
    }

    private Predicate seriesAllRead(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Long userId) {
        Subquery<Long> notReadSub = query.subquery(Long.class);
        Root<BookEntity> nrRoot = notReadSub.from(BookEntity.class);
        Join<Object, Object> nrProgress = nrRoot.join(FIELD_USER_BOOK_PROGRESS, JoinType.INNER);
        notReadSub.select(cb.literal(1L)).where(
                cb.equal(nrRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.equal(nrProgress.get("user").get("id"), userId),
                cb.notEqual(nrProgress.get(FIELD_READ_STATUS), ReadStatus.READ)
        );

        return cb.and(
                seriesHasReadStatus(query, cb, root, userId, List.of("READ")),
                cb.not(cb.exists(notReadSub))
        );
    }

    private Predicate seriesOwnsLastBook(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root) {
        Subquery<Integer> totalSub = query.subquery(Integer.class);
        Root<BookEntity> totalRoot = totalSub.from(BookEntity.class);
        totalSub.select(cb.max(totalRoot.get(FIELD_METADATA).get(FIELD_SERIES_TOTAL))).where(
                cb.equal(totalRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(totalRoot.get(FIELD_METADATA).get(FIELD_SERIES_TOTAL))
        );

        Subquery<Long> existsSub = query.subquery(Long.class);
        Root<BookEntity> subRoot = existsSub.from(BookEntity.class);
        existsSub.select(cb.literal(1L)).where(
                cb.equal(subRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.equal(
                        cb.function(FUNCTION_FLOOR, Integer.class, subRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER)),
                        totalSub
                )
        );
        return cb.exists(existsSub);
    }

    private Predicate seriesHasTotal(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<BookEntity> subRoot = sub.from(BookEntity.class);
        sub.select(cb.literal(1L)).where(
                cb.equal(subRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(subRoot.get(FIELD_METADATA).get(FIELD_SERIES_TOTAL))
        );
        return cb.exists(sub);
    }

    private Predicate buildSeriesGapsPredicate(String value, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Predicate hasSeries) {
        Predicate condition = switch (value) {
            case "any_gap" -> seriesHasAnyGap(query, cb, root);
            case "missing_first" -> seriesMissingFirst(query, cb, root);
            case "missing_latest" -> cb.and(seriesHasTotal(query, cb, root), cb.not(seriesOwnsLastBook(query, cb, root)));
            case "duplicate_number" -> seriesHasDuplicateNumber(query, cb, root);
            default -> cb.conjunction();
        };
        return cb.and(hasSeries, condition);
    }

    private Predicate seriesHasAnyGap(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root) {
        Subquery<Long> countSub = query.subquery(Long.class);
        Root<BookEntity> cRoot = countSub.from(BookEntity.class);
        countSub.select(cb.countDistinct(cb.function(FUNCTION_FLOOR, Integer.class, cRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER)))).where(
                cb.equal(cRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(cRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER))
        );

        Subquery<Integer> maxSub = query.subquery(Integer.class);
        Root<BookEntity> mRoot = maxSub.from(BookEntity.class);
        maxSub.select(cb.max(cb.function(FUNCTION_FLOOR, Integer.class, mRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER)))).where(
                cb.equal(mRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(mRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER))
        );

        return cb.lt(countSub, maxSub.as(Long.class));
    }

    private Predicate seriesMissingFirst(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<BookEntity> subRoot = sub.from(BookEntity.class);
        sub.select(cb.literal(1L)).where(
                cb.equal(subRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.equal(cb.function(FUNCTION_FLOOR, Integer.class, subRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER)), 1)
        );
        return cb.not(cb.exists(sub));
    }

    private Predicate seriesHasDuplicateNumber(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root) {
        Subquery<Long> totalSub = query.subquery(Long.class);
        Root<BookEntity> tRoot = totalSub.from(BookEntity.class);
        totalSub.select(cb.count(tRoot)).where(
                cb.equal(tRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(tRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER))
        );

        Subquery<Long> distinctSub = query.subquery(Long.class);
        Root<BookEntity> dRoot = distinctSub.from(BookEntity.class);
        distinctSub.select(cb.countDistinct(dRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER))).where(
                cb.equal(dRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(dRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER))
        );

        return cb.gt(totalSub, distinctSub);
    }

    private Predicate buildSeriesPositionPredicate(String value, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin, Predicate hasSeries, Long userId) {
        Predicate hasNumber = cb.isNotNull(root.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER));
        Predicate condition = switch (value) {
            case "next_unread" -> isNextUnread(query, cb, root, progressJoin, userId);
            case "first_in_series" -> isFirstInSeries(query, cb, root);
            case "last_in_series" -> isLastInSeries(query, cb, root);
            default -> cb.conjunction();
        };
        return cb.and(hasSeries, hasNumber, condition);
    }

    private Predicate isFirstInSeries(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root) {
        Subquery<Float> minSub = query.subquery(Float.class);
        Root<BookEntity> mRoot = minSub.from(BookEntity.class);
        minSub.select(cb.min(mRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER))).where(
                cb.equal(mRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(mRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER))
        );
        return cb.equal(root.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER), minSub);
    }

    private Predicate isLastInSeries(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root) {
        Subquery<Float> maxSub = query.subquery(Float.class);
        Root<BookEntity> mRoot = maxSub.from(BookEntity.class);
        maxSub.select(cb.max(mRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER))).where(
                cb.equal(mRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(mRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER))
        );
        return cb.equal(root.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER), maxSub);
    }

    private Predicate isNextUnread(CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin, Long userId) {
        Predicate notRead = cb.or(
                cb.isNull(progressJoin.get(FIELD_READ_STATUS)),
                cb.notEqual(progressJoin.get(FIELD_READ_STATUS), ReadStatus.READ)
        );

        Subquery<Long> lowerUnreadSub = query.subquery(Long.class);
        Root<BookEntity> luRoot = lowerUnreadSub.from(BookEntity.class);
        Join<Object, Object> luProgress = luRoot.join(FIELD_USER_BOOK_PROGRESS, JoinType.LEFT);
        lowerUnreadSub.select(cb.literal(1L)).where(
                cb.equal(luRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(luRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER)),
                cb.lt(luRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER), root.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER)),
                cb.or(
                        cb.isNull(luProgress.get(FIELD_READ_STATUS)),
                        cb.notEqual(luProgress.get(FIELD_READ_STATUS), ReadStatus.READ)
                ),
                cb.or(
                        cb.isNull(luProgress.get("user").get("id")),
                        cb.equal(luProgress.get("user").get("id"), userId)
                )
        );
        Predicate noLowerUnread = cb.not(cb.exists(lowerUnreadSub));

        Subquery<Long> priorReadSub = query.subquery(Long.class);
        Root<BookEntity> prRoot = priorReadSub.from(BookEntity.class);
        Join<Object, Object> prProgress = prRoot.join(FIELD_USER_BOOK_PROGRESS, JoinType.INNER);
        priorReadSub.select(cb.literal(1L)).where(
                cb.equal(prRoot.get(FIELD_METADATA).get(FIELD_SERIES_NAME), root.get(FIELD_METADATA).get(FIELD_SERIES_NAME)),
                cb.isNotNull(prRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER)),
                cb.lt(prRoot.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER), root.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER)),
                cb.equal(prProgress.get("user").get("id"), userId),
                cb.equal(prProgress.get(FIELD_READ_STATUS), ReadStatus.READ)
        );
        Predicate hasPriorRead = cb.exists(priorReadSub);

        return cb.and(notRead, noLowerUnread, hasPriorRead);
    }

    private Predicate buildEquals(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        List<String> ruleList = toStringList(rule.getValue());

        if (isArrayField(rule.getField())) {
            return buildArrayFieldPredicate(rule.getField(), ruleList, query, cb, root, false);
        }

        Expression<?> field = getFieldExpression(rule.getField(), cb, root, progressJoin);
        if (field == null) return cb.conjunction();

        Object value = normalizeValue(rule.getValue(), rule.getField());
        if (value == null) return cb.conjunction();

        if (value instanceof Boolean) {
            return cb.equal(field, value);
        } else if (value instanceof LocalDate) {
            return cb.equal(field, value);
        } else if (value instanceof LocalDateTime) {
            return cb.equal(field, value);
        } else if (rule.getField() == RuleField.READ_STATUS) {
            if (READ_STATUS_UNSET.equals(value.toString())) {
                return cb.isNull(field);
            }
            ReadStatus status = ReadStatus.valueOf(value.toString());
            return cb.equal(field, status);
        } else if (value instanceof Number) {
            return cb.equal(field, value);
        }
        return cb.equal(cb.lower(field.as(String.class)), value.toString().toLowerCase());
    }

    private Predicate buildNotEquals(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        Predicate negated = cb.not(buildEquals(rule, query, cb, root, progressJoin));
        if (rule.getField() == RuleField.READ_STATUS && !READ_STATUS_UNSET.equals(String.valueOf(rule.getValue()))) {
            return cb.or(cb.isNull(progressJoin.get(FIELD_READ_STATUS)), negated);
        }
        return negated;
    }

    private Predicate buildContains(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        String ruleVal = rule.getValue().toString().toLowerCase();
        return buildStringPredicate(rule.getField(), query, root, progressJoin, cb,
                nameField -> cb.like(cb.lower(nameField), "%" + escapeLike(ruleVal) + "%"));
    }

    private Predicate buildStartsWith(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        String ruleVal = rule.getValue().toString().toLowerCase();
        return buildStringPredicate(rule.getField(), query, root, progressJoin, cb,
                nameField -> cb.like(cb.lower(nameField), escapeLike(ruleVal) + "%"));
    }

    private Predicate buildEndsWith(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        String ruleVal = rule.getValue().toString().toLowerCase();
        return buildStringPredicate(rule.getField(), query, root, progressJoin, cb,
                nameField -> cb.like(cb.lower(nameField), "%" + escapeLike(ruleVal)));
    }

    private Predicate buildStringPredicate(RuleField field, CriteriaQuery<?> query, Root<BookEntity> root,
                                           Join<BookEntity, UserBookProgressEntity> progressJoin,
                                           CriteriaBuilder cb,
                                           Function<Expression<String>, Predicate> predicateBuilder) {
        if (isArrayField(field)) {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<BookEntity> subRoot = subquery.from(BookEntity.class);
            Join<?, ?> arrayJoin = createArrayFieldJoin(field, subRoot);
            Expression<String> nameField = getArrayFieldNameExpression(field, arrayJoin);
            subquery.select(cb.literal(1L)).where(
                    cb.equal(subRoot.get("id"), root.get("id")),
                    predicateBuilder.apply(nameField)
            );
            return cb.exists(subquery);
        }

        Expression<?> fieldExpr = getFieldExpression(field, cb, root, progressJoin);
        if (fieldExpr == null) return cb.conjunction();

        return predicateBuilder.apply(fieldExpr.as(String.class));
    }

    private Predicate buildGreaterThan(Rule rule, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        return buildComparisonPredicate(rule, cb, root, progressJoin,
                (field, dateValue) -> cb.greaterThan(field.as(LocalDateTime.class), dateValue),
                (field, localDateValue) -> cb.greaterThan(field.as(LocalDate.class), localDateValue),
                (field, numValue) -> cb.gt(toNumericExpression(field), numValue));
    }

    private Predicate buildGreaterThanEqual(Rule rule, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        return buildComparisonPredicate(rule, cb, root, progressJoin,
                (field, dateValue) -> cb.greaterThanOrEqualTo(field.as(LocalDateTime.class), dateValue),
                (field, localDateValue) -> cb.greaterThanOrEqualTo(field.as(LocalDate.class), localDateValue),
                (field, numValue) -> cb.ge(toNumericExpression(field), numValue));
    }

    private Predicate buildLessThan(Rule rule, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        return buildComparisonPredicate(rule, cb, root, progressJoin,
                (field, dateValue) -> cb.lessThan(field.as(LocalDateTime.class), dateValue),
                (field, localDateValue) -> cb.lessThan(field.as(LocalDate.class), localDateValue),
                (field, numValue) -> cb.lt(toNumericExpression(field), numValue));
    }

    private Predicate buildLessThanEqual(Rule rule, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        return buildComparisonPredicate(rule, cb, root, progressJoin,
                (field, dateValue) -> cb.lessThanOrEqualTo(field.as(LocalDateTime.class), dateValue),
                (field, localDateValue) -> cb.lessThanOrEqualTo(field.as(LocalDate.class), localDateValue),
                (field, numValue) -> cb.le(toNumericExpression(field), numValue));
    }

    private Predicate buildComparisonPredicate(Rule rule, CriteriaBuilder cb, Root<BookEntity> root,
                                               Join<BookEntity, UserBookProgressEntity> progressJoin,
                                               BiFunction<Expression<?>, LocalDateTime, Predicate> dateTimeComparator,
                                               BiFunction<Expression<?>, LocalDate, Predicate> dateComparator,
                                               BiFunction<Expression<?>, Double, Predicate> numberComparator) {
        Expression<?> field = getFieldExpression(rule.getField(), cb, root, progressJoin);
        if (field == null) return cb.conjunction();

        Object value = normalizeValue(rule.getValue(), rule.getField());

        if (value instanceof LocalDate localdate) {
            return dateComparator.apply(field, localdate);
        }
        if (value instanceof LocalDateTime localdatetime) {
            return dateTimeComparator.apply(field, localdatetime);
        }
        if (!(value instanceof Number)) return cb.conjunction();
        return numberComparator.apply(field, ((Number) value).doubleValue());
    }

    private Predicate buildInBetween(Rule rule, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        Expression<?> field = getFieldExpression(rule.getField(), cb, root, progressJoin);
        if (field == null) return cb.conjunction();

        Object start = normalizeValue(rule.getValueStart(), rule.getField());
        Object end = normalizeValue(rule.getValueEnd(), rule.getField());

        if (start == null || end == null) return cb.conjunction();

        if (start instanceof LocalDate startDate && end instanceof LocalDate endDate) {
            return cb.between(field.as(LocalDate.class), startDate, endDate);
        }

        if (start instanceof LocalDateTime startDateTime && end instanceof LocalDateTime endDateTime) {
            return cb.between(field.as(LocalDateTime.class), startDateTime, endDateTime);
        }

        if (!(start instanceof Number) || !(end instanceof Number)) {
            return cb.conjunction();
        }

        @SuppressWarnings("unchecked")
        Expression<Double> numField = (Expression<Double>) (Expression<?>) field;
        return cb.between(numField, ((Number) start).doubleValue(), ((Number) end).doubleValue());
    }

    private Predicate buildIsEmpty(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        if (isArrayField(rule.getField())) {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<BookEntity> subRoot = subquery.from(BookEntity.class);

            if (rule.getField() == RuleField.SHELF) {
                subRoot.join("shelves", JoinType.INNER);
            } else {
                Join<Object, Object> metadataJoin = subRoot.join(FIELD_METADATA, JoinType.INNER);
                joinArrayField(rule.getField(), metadataJoin);
            }

            subquery.select(cb.literal(1L)).where(cb.equal(subRoot.get("id"), root.get("id")));

            return cb.not(cb.exists(subquery));
        }

        Expression<?> field = getFieldExpression(rule.getField(), cb, root, progressJoin);
        if (field == null) return cb.conjunction();

        return cb.or(cb.isNull(field), cb.equal(cb.trim(field.as(String.class)), ""));
    }

    private Predicate buildIncludesAny(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        List<String> ruleList = toStringList(rule.getValue());

        if (isArrayField(rule.getField())) {
            return buildArrayFieldPredicate(rule.getField(), ruleList, query, cb, root, false);
        }

        return buildFieldInPredicate(rule.getField(), field -> field, ruleList, cb, root, progressJoin);
    }

    private Predicate buildExcludesAll(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        List<String> ruleList = toStringList(rule.getValue());

        if (isArrayField(rule.getField())) {
            return cb.not(buildArrayFieldPredicate(rule.getField(), ruleList, query, cb, root, false));
        }

        Predicate negated = cb.not(buildFieldInPredicate(rule.getField(), field -> field, ruleList, cb, root, progressJoin));
        if (rule.getField() == RuleField.READ_STATUS && ruleList.stream().noneMatch(READ_STATUS_UNSET::equals)) {
            return cb.or(cb.isNull(progressJoin.get(FIELD_READ_STATUS)), negated);
        }
        return negated;
    }

    private Predicate buildIncludesAll(Rule rule, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        List<String> ruleList = toStringList(rule.getValue());

        if (isArrayField(rule.getField())) {
            return buildArrayFieldPredicate(rule.getField(), ruleList, query, cb, root, true);
        }

        return buildFieldInPredicate(rule.getField(), field -> field, ruleList, cb, root, progressJoin);
    }

    private Predicate buildFieldInPredicate(RuleField ruleField,
                                            Function<Expression<?>, Expression<?>> fieldTransformer,
                                            List<String> ruleList,
                                            CriteriaBuilder cb,
                                            Root<BookEntity> root,
                                            Join<BookEntity, UserBookProgressEntity> progressJoin) {
        Expression<?> field = fieldTransformer.apply(getFieldExpression(ruleField, cb, root, progressJoin));
        if (field == null) return cb.conjunction();

        if (ruleField == RuleField.READ_STATUS) {
            boolean hasUnset = ruleList.stream().anyMatch(READ_STATUS_UNSET::equals);
            List<String> nonUnsetValues = ruleList.stream()
                    .filter(v -> !READ_STATUS_UNSET.equals(v))
                    .toList();

            List<ReadStatus> statuses = nonUnsetValues.stream()
                    .map(ReadStatus::valueOf)
                    .toList();

            if (hasUnset && !statuses.isEmpty()) {
                return cb.or(
                        cb.isNull(field),
                        field.in(statuses)
                );
            } else if (hasUnset) {
                return cb.isNull(field);
            } else {
                return field.in(statuses);
            }
        }

        List<String> lowerList = ruleList.stream().map(String::toLowerCase).toList();
        return cb.lower(field.as(String.class)).in(lowerList);
    }

    private Expression<?> getFieldExpression(RuleField field, CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, UserBookProgressEntity> progressJoin) {
        return switch (field) {
            case LIBRARY -> root.get("library").get("id");
            case SHELF -> null;
            case READ_STATUS -> progressJoin.get(FIELD_READ_STATUS);
            case DATE_FINISHED -> progressJoin.get("dateFinished");
            case LAST_READ_TIME -> progressJoin.get("lastReadTime");
            case PERSONAL_RATING -> progressJoin.get(FIELD_PERSONAL_RATING);
            case FILE_SIZE -> root.join(FIELD_BOOK_FILES, JoinType.LEFT).get("fileSizeKb");
            case METADATA_SCORE -> root.get("metadataMatchScore");
            case TITLE -> root.get(FIELD_METADATA).get("title");
            case SUBTITLE -> root.get(FIELD_METADATA).get("subtitle");
            case PUBLISHER -> root.get(FIELD_METADATA).get("publisher");
            case PUBLISHED_DATE -> root.get(FIELD_METADATA).get("publishedDate");
            case PAGE_COUNT -> root.get(FIELD_METADATA).get("pageCount");
            case LANGUAGE -> root.get(FIELD_METADATA).get("language");
            case SERIES_NAME -> root.get(FIELD_METADATA).get(FIELD_SERIES_NAME);
            case SERIES_NUMBER -> root.get(FIELD_METADATA).get(FIELD_SERIES_NUMBER);
            case SERIES_TOTAL -> root.get(FIELD_METADATA).get(FIELD_SERIES_TOTAL);
            case ISBN13 -> root.get(FIELD_METADATA).get("isbn13");
            case ISBN10 -> root.get(FIELD_METADATA).get("isbn10");
            case AMAZON_RATING -> root.get(FIELD_METADATA).get("amazonRating");
            case AMAZON_REVIEW_COUNT -> root.get(FIELD_METADATA).get("amazonReviewCount");
            case GOODREADS_RATING -> root.get(FIELD_METADATA).get("goodreadsRating");
            case GOODREADS_REVIEW_COUNT -> root.get(FIELD_METADATA).get("goodreadsReviewCount");
            case HARDCOVER_RATING -> root.get(FIELD_METADATA).get("hardcoverRating");
            case HARDCOVER_REVIEW_COUNT -> root.get(FIELD_METADATA).get("hardcoverReviewCount");
            case RANOBEDB_RATING -> root.get(FIELD_METADATA).get("ranobedbRating");
            case AGE_RATING -> root.get(FIELD_METADATA).get("ageRating");
            case CONTENT_RATING -> root.get(FIELD_METADATA).get("contentRating");
            case ADDED_ON -> root.get("addedOn");
            case LUBIMYCZYTAC_RATING -> root.get(FIELD_METADATA).get("lubimyczytacRating");
            case DESCRIPTION -> root.get(FIELD_METADATA).get("description");
            case NARRATOR -> root.get(FIELD_METADATA).get("narrator");
            case AUDIBLE_RATING -> root.get(FIELD_METADATA).get("audibleRating");
            case AUDIBLE_REVIEW_COUNT -> root.get(FIELD_METADATA).get("audibleReviewCount");
            case ABRIDGED -> root.get(FIELD_METADATA).get("abridged");
            case AUDIOBOOK_DURATION -> root.join(FIELD_BOOK_FILES, JoinType.LEFT).get("durationSeconds");
            case AUDIOBOOK_CODEC -> root.join(FIELD_BOOK_FILES, JoinType.LEFT).get("codec");
            case AUDIOBOOK_CHAPTER_COUNT -> root.join(FIELD_BOOK_FILES, JoinType.LEFT).get("chapterCount");
            case AUDIOBOOK_BITRATE -> root.join(FIELD_BOOK_FILES, JoinType.LEFT).get("bitrate");
            case IS_PHYSICAL -> root.get("isPhysical");
            case READING_PROGRESS -> {
                Expression<Float> koreader = cb.coalesce(progressJoin.get("koreaderProgressPercent"), 0f);
                Expression<Float> kobo = cb.coalesce(progressJoin.get("koboProgressPercent"), 0f);
                Expression<Float> pdf = cb.coalesce(progressJoin.get("pdfProgressPercent"), 0f);
                Expression<Float> epub = cb.coalesce(progressJoin.get("epubProgressPercent"), 0f);
                Expression<Float> cbx = cb.coalesce(progressJoin.get("cbxProgressPercent"), 0f);
                yield cb.function("GREATEST", Float.class, koreader, kobo, pdf, epub, cbx);
            }
            case FILE_TYPE -> cb.function("SUBSTRING_INDEX", String.class,
                    root.join(FIELD_BOOK_FILES, JoinType.LEFT).get("fileName"), cb.literal("."), cb.literal(-1));
            default -> null;
        };
    }

    private boolean isArrayField(RuleField field) {
        return field == RuleField.AUTHORS || field == RuleField.CATEGORIES ||
                field == RuleField.MOODS || field == RuleField.TAGS ||
                field == RuleField.GENRE || field == RuleField.SHELF;
    }

    private Join<?, ?> createArrayFieldJoin(RuleField field, Root<BookEntity> root) {
        if (field == RuleField.SHELF) {
            return root.join("shelves", JoinType.INNER);
        }
        Join<Object, Object> metadataJoin = root.join(FIELD_METADATA, JoinType.INNER);
        return joinArrayField(field, metadataJoin);
    }

    private Expression<String> getArrayFieldNameExpression(RuleField field, Join<?, ?> arrayJoin) {
        if (field == RuleField.SHELF) {
            return arrayJoin.get("id").as(String.class);
        }
        return arrayJoin.get("name");
    }

    private Join<?, ?> joinArrayField(RuleField field, Join<Object, Object> metadataJoin) {
        return switch (field) {
            case AUTHORS -> metadataJoin.join(FIELD_AUTHORS, JoinType.INNER);
            case CATEGORIES -> metadataJoin.join(FIELD_CATEGORIES, JoinType.INNER);
            case MOODS -> metadataJoin.join(FIELD_MOODS, JoinType.INNER);
            case TAGS -> metadataJoin.join("tags", JoinType.INNER);
            case GENRE -> metadataJoin.join(FIELD_CATEGORIES, JoinType.INNER);
            default -> throw new IllegalArgumentException("Not an array field: " + field);
        };
    }

    private Predicate buildArrayFieldPredicate(RuleField field, List<String> values, CriteriaQuery<?> query, CriteriaBuilder cb, Root<BookEntity> root, boolean includesAll) {
        if (values.isEmpty()) {
            return cb.conjunction();
        }
        if (includesAll) {
            List<Predicate> predicates = values.stream()
                    .map(value -> {
                        Subquery<Long> subquery = query.subquery(Long.class);
                        Root<BookEntity> subRoot = subquery.from(BookEntity.class);
                        Join<?, ?> arrayJoin = createArrayFieldJoin(field, subRoot);
                        Expression<String> nameField = getArrayFieldNameExpression(field, arrayJoin);
                        subquery.select(cb.literal(1L)).where(
                                cb.equal(subRoot.get("id"), root.get("id")),
                                cb.equal(cb.lower(nameField), value.toLowerCase())
                        );
                        return cb.exists(subquery);
                    })
                    .toList();

            return cb.and(predicates.toArray(Predicate[]::new));
        } else {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<BookEntity> subRoot = subquery.from(BookEntity.class);
            Join<?, ?> arrayJoin = createArrayFieldJoin(field, subRoot);
            Expression<String> nameField = getArrayFieldNameExpression(field, arrayJoin);

            List<String> lowerValues = values.stream()
                    .map(String::toLowerCase)
                    .toList();

            subquery.select(cb.literal(1L)).where(
                    cb.equal(subRoot.get("id"), root.get("id")),
                    cb.lower(nameField).in(lowerValues)
            );
            return cb.exists(subquery);
        }
    }

    private Object normalizeValue(Object value, RuleField field) {
        if (value == null) return null;

        if (field == RuleField.PUBLISHED_DATE) {
            LocalDateTime parsed = parseDate(value);
            return parsed != null ? parsed.toLocalDate() : null;
        }

        if (field == RuleField.DATE_FINISHED || field == RuleField.LAST_READ_TIME || field == RuleField.ADDED_ON) {
            LocalDateTime parsed = parseDate(value);
            if (parsed != null) {
                return parsed.atZone(ZoneId.systemDefault()).toInstant();
            }
            return null;
        }

        if (field == RuleField.READ_STATUS) {
            return value.toString();
        }

        if (field == RuleField.ABRIDGED || field == RuleField.IS_PHYSICAL) {
            return Boolean.valueOf(value.toString());
        }

        if (value instanceof Number) {
            return value;
        }

        if (isNumericField(field)) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException _) {
                // not a number, fall through to string handling
            }
        }

        return value.toString().toLowerCase();
    }

    private LocalDateTime parseDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime localdatetime) return localdatetime;

        try {
            return LocalDateTime.parse(value.toString(), DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception _) {
            try {
                return LocalDate.parse(value.toString()).atStartOfDay();
            } catch (Exception _) {
                return null;
            }
        }
    }

    private List<String> toStringList(Object value) {
        if (value == null) return Collections.emptyList();
        if (value instanceof List) {
            return ((Collection<?>) value).stream()
                    .map(Object::toString)
                    .toList();
        }

        return Collections.singletonList(value.toString());
    }

    private static final Set<RuleField> NUMERIC_FIELDS = Set.of(
            RuleField.METADATA_SCORE, RuleField.FILE_SIZE, RuleField.PAGE_COUNT,
            RuleField.SERIES_NUMBER, RuleField.SERIES_TOTAL, RuleField.AGE_RATING,
            RuleField.PERSONAL_RATING, RuleField.READING_PROGRESS, RuleField.AUDIOBOOK_DURATION,
            RuleField.AMAZON_RATING, RuleField.AMAZON_REVIEW_COUNT,
            RuleField.GOODREADS_RATING, RuleField.GOODREADS_REVIEW_COUNT,
            RuleField.HARDCOVER_RATING, RuleField.HARDCOVER_REVIEW_COUNT,
            RuleField.LUBIMYCZYTAC_RATING, RuleField.RANOBEDB_RATING,
            RuleField.AUDIBLE_RATING, RuleField.AUDIBLE_REVIEW_COUNT,
            RuleField.AUDIOBOOK_CHAPTER_COUNT, RuleField.AUDIOBOOK_BITRATE
    );

    private static boolean isNumericField(RuleField field) {
        return field != null && NUMERIC_FIELDS.contains(field);
    }

    @SuppressWarnings("unchecked")
    private static Expression<Number> toNumericExpression(Expression<?> expr) {
        return (Expression<Number>) expr;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
