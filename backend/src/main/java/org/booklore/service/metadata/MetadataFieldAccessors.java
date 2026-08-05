package org.booklore.service.metadata;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.MetadataField;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads a {@link MetadataField} out of a {@link BookMetadata}.
 * <p>
 * The provenance rules are all stated in terms of values — "record the provider only if the value it
 * proposed is the value that is actually stored" — and on the proposal path there is no updater to ask,
 * because a proposal is accepted by a second, separate request. So the comparison has to be made here,
 * and that needs a way to name a field's value generically.
 * <p>
 * Deliberately one table, not two. Comparing the proposal against the *entity* would need a second,
 * parallel set of accessors that could drift out of step with this one; instead the current state is
 * mapped to a {@code BookMetadata} first and both sides are read through these same functions, so a
 * mismatch is impossible by construction.
 * <p>
 * Every constant of {@code MetadataField} must appear here — a missing entry would silently drop that
 * field's attribution rather than fail, which is exactly the quiet half-behaviour this feature must not
 * have. {@code MetadataFieldAccessorsTest} fails the build if one is ever added without an accessor.
 */
public final class MetadataFieldAccessors {

    private static final Map<MetadataField, Function<BookMetadata, Object>> ACCESSORS = buildAccessors();

    private MetadataFieldAccessors() {
    }

    private static Map<MetadataField, Function<BookMetadata, Object>> buildAccessors() {
        EnumMap<MetadataField, Function<BookMetadata, Object>> accessors = new EnumMap<>(MetadataField.class);
        accessors.put(MetadataField.TITLE, BookMetadata::getTitle);
        accessors.put(MetadataField.SUBTITLE, BookMetadata::getSubtitle);
        accessors.put(MetadataField.DESCRIPTION, BookMetadata::getDescription);
        accessors.put(MetadataField.PUBLISHER, BookMetadata::getPublisher);
        accessors.put(MetadataField.PUBLISHED_DATE, BookMetadata::getPublishedDate);
        accessors.put(MetadataField.SERIES_NAME, BookMetadata::getSeriesName);
        accessors.put(MetadataField.SERIES_NUMBER, BookMetadata::getSeriesNumber);
        accessors.put(MetadataField.SERIES_TOTAL, BookMetadata::getSeriesTotal);
        accessors.put(MetadataField.ISBN_13, BookMetadata::getIsbn13);
        accessors.put(MetadataField.ISBN_10, BookMetadata::getIsbn10);
        accessors.put(MetadataField.LANGUAGE, BookMetadata::getLanguage);
        accessors.put(MetadataField.PAGE_COUNT, BookMetadata::getPageCount);
        accessors.put(MetadataField.ASIN, BookMetadata::getAsin);
        accessors.put(MetadataField.GOODREADS_ID, BookMetadata::getGoodreadsId);
        accessors.put(MetadataField.COMICVINE_ID, BookMetadata::getComicvineId);
        accessors.put(MetadataField.HARDCOVER_ID, BookMetadata::getHardcoverId);
        accessors.put(MetadataField.HARDCOVER_BOOK_ID, BookMetadata::getHardcoverBookId);
        accessors.put(MetadataField.GOOGLE_ID, BookMetadata::getGoogleId);
        accessors.put(MetadataField.LUBIMYCZYTAC_ID, BookMetadata::getLubimyczytacId);
        accessors.put(MetadataField.RANOBEDB_ID, BookMetadata::getRanobedbId);
        accessors.put(MetadataField.AUDIBLE_ID, BookMetadata::getAudibleId);
        accessors.put(MetadataField.AMAZON_RATING, BookMetadata::getAmazonRating);
        accessors.put(MetadataField.AMAZON_REVIEW_COUNT, BookMetadata::getAmazonReviewCount);
        accessors.put(MetadataField.GOODREADS_RATING, BookMetadata::getGoodreadsRating);
        accessors.put(MetadataField.GOODREADS_REVIEW_COUNT, BookMetadata::getGoodreadsReviewCount);
        accessors.put(MetadataField.HARDCOVER_RATING, BookMetadata::getHardcoverRating);
        accessors.put(MetadataField.HARDCOVER_REVIEW_COUNT, BookMetadata::getHardcoverReviewCount);
        accessors.put(MetadataField.LUBIMYCZYTAC_RATING, BookMetadata::getLubimyczytacRating);
        accessors.put(MetadataField.RANOBEDB_RATING, BookMetadata::getRanobedbRating);
        accessors.put(MetadataField.AUDIBLE_RATING, BookMetadata::getAudibleRating);
        accessors.put(MetadataField.AUDIBLE_REVIEW_COUNT, BookMetadata::getAudibleReviewCount);
        return Collections.unmodifiableMap(accessors);
    }

    /**
     * The field's value, or null when the metadata is null or the field is not readable.
     */
    public static Object valueOf(MetadataField field, BookMetadata metadata) {
        if (field == null || metadata == null) {
            return null;
        }
        Function<BookMetadata, Object> accessor = ACCESSORS.get(field);
        return accessor == null ? null : accessor.apply(metadata);
    }

    public static boolean covers(MetadataField field) {
        return ACCESSORS.containsKey(field);
    }
}
