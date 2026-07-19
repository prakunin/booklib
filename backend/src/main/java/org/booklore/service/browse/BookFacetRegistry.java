package org.booklore.service.browse;

import org.booklore.browse.FacetLogic;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.service.opds.MagicShelfBookService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class BookFacetRegistry {

    private static final String MAGIC_SHELF_PREFIX = "magic:";

    private static final Set<String> NAMES = Set.of(
            "author", "series", "genre", "tag", "mood", "language", "publisher", "library", "shelf",
            "file_type", "read_status", "personal_rating", "amazon_rating", "goodreads_rating",
            "hardcover_rating", "ranobedb_rating", "age_rating", "content_rating", "match_score",
            "published_year", "file_size", "page_count", "shelf_status",
            "comic_character", "comic_team", "comic_location", "comic_creator");

    private final MagicShelfBookService magicShelfBookService;

    public BookFacetRegistry(MagicShelfBookService magicShelfBookService) {
        this.magicShelfBookService = magicShelfBookService;
    }

    public boolean has(String facetName) {
        return NAMES.contains(facetName);
    }

    public Set<String> facetNames() {
        return NAMES;
    }

    public Specification<BookEntity> toSpecification(String facetName, List<String> values, FacetLogic logic, Long userId) {
        String mode = mode(logic);
        return switch (facetName) {
            case "author" -> BookSpecifications.withAuthors(values, mode);
            case "series" -> BookSpecifications.inSeriesMulti(values, mode);
            case "genre" -> BookSpecifications.withCategories(values, mode);
            case "tag" -> BookSpecifications.withTags(values, mode);
            case "mood" -> BookSpecifications.withMoods(values, mode);
            case "language" -> BookSpecifications.withLanguages(values, mode);
            case "publisher" -> BookSpecifications.withPublishers(values, mode);
            case "library" -> BookSpecifications.inLibraries(values, mode);
            case "shelf" -> shelves(values, logic, userId);
            case "file_type" -> BookSpecifications.withFileTypes(values, mode);
            case "read_status" -> BookSpecifications.withReadStatuses(values, userId, mode);
            case "personal_rating" -> BookSpecifications.withPersonalRatings(values, userId, mode);
            case "amazon_rating" -> BookSpecifications.withAmazonRatings(values, mode);
            case "goodreads_rating" -> BookSpecifications.withGoodreadsRatings(values, mode);
            case "hardcover_rating" -> BookSpecifications.withHardcoverRatings(values, mode);
            case "ranobedb_rating" -> BookSpecifications.withRanobedbRatings(values, mode);
            case "age_rating" -> BookSpecifications.withAgeRatings(values, mode);
            case "content_rating" -> BookSpecifications.withContentRatings(values, mode);
            case "match_score" -> BookSpecifications.withMatchScores(values, mode);
            case "published_year" -> BookSpecifications.withPublishedYears(values, mode);
            case "file_size" -> BookSpecifications.withFileSizes(values, mode);
            case "page_count" -> BookSpecifications.withPageCounts(values, mode);
            case "shelf_status" -> BookSpecifications.withShelfStatus(values, mode);
            case "comic_character" -> BookSpecifications.withComicCharacters(values, mode);
            case "comic_team" -> BookSpecifications.withComicTeams(values, mode);
            case "comic_location" -> BookSpecifications.withComicLocations(values, mode);
            case "comic_creator" -> BookSpecifications.withComicCreators(values, mode);
            default -> throw ApiError.INVALID_FACET.createException("Unknown facet: " + facetName);
        };
    }

    private Specification<BookEntity> shelves(List<String> values, FacetLogic logic, Long userId) {
        List<String> regularIds = new ArrayList<>();
        List<Specification<BookEntity>> specs = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.startsWith(MAGIC_SHELF_PREFIX)) {
                specs.add(magicShelfBookService.toSpecification(userId, parseMagicShelfId(value)));
            } else {
                regularIds.add(value);
            }
        }
        if (!regularIds.isEmpty()) {
            String inMode = logic == FacetLogic.AND ? "and" : "or";
            specs.add(BookSpecifications.inShelves(regularIds, inMode));
        }
        if (specs.isEmpty()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return switch (logic) {
            case OR -> Specification.anyOf(specs);
            case NOT -> Specification.not(Specification.anyOf(specs));
            case AND -> Specification.allOf(specs);
        };
    }

    private static long parseMagicShelfId(String value) {
        try {
            return Long.parseLong(value.substring(MAGIC_SHELF_PREFIX.length()));
        } catch (NumberFormatException _) {
            throw ApiError.INVALID_FACET.createException("Invalid magic shelf id: " + value);
        }
    }

    private static String mode(FacetLogic logic) {
        return switch (logic) {
            case OR -> "or";
            case NOT -> "not";
            case AND -> "and";
        };
    }
}
