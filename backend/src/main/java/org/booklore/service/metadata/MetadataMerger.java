package org.booklore.service.metadata;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.BookReview;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.enums.MetadataField;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.MetadataReplaceMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.booklore.model.enums.MetadataProvider.*;

class MetadataMerger {

    private final List<FieldMergeSpec> fieldMergeSpecs = List.of(
            resolvedString(MetadataField.TITLE, MetadataRefreshOptions.EnabledFields::isTitle, MetadataRefreshOptions.FieldOptions::getTitle,
                    BookMetadata::getTitle, BookMetadata::setTitle),
            resolvedString(MetadataField.SUBTITLE, MetadataRefreshOptions.EnabledFields::isSubtitle, MetadataRefreshOptions.FieldOptions::getSubtitle,
                    BookMetadata::getSubtitle, BookMetadata::setSubtitle),
            resolvedString(MetadataField.DESCRIPTION, MetadataRefreshOptions.EnabledFields::isDescription, MetadataRefreshOptions.FieldOptions::getDescription,
                    BookMetadata::getDescription, BookMetadata::setDescription),
            resolvedList(MetadataRefreshOptions.EnabledFields::isAuthors, MetadataRefreshOptions.FieldOptions::getAuthors,
                    BookMetadata::getAuthors, BookMetadata::setAuthors),
            resolvedString(MetadataField.PUBLISHER, MetadataRefreshOptions.EnabledFields::isPublisher, MetadataRefreshOptions.FieldOptions::getPublisher,
                    BookMetadata::getPublisher, BookMetadata::setPublisher),
            resolved(MetadataField.PUBLISHED_DATE, MetadataRefreshOptions.EnabledFields::isPublishedDate, MetadataRefreshOptions.FieldOptions::getPublishedDate,
                    BookMetadata::getPublishedDate, BookMetadata::setPublishedDate),
            resolvedString(MetadataField.SERIES_NAME, MetadataRefreshOptions.EnabledFields::isSeriesName, MetadataRefreshOptions.FieldOptions::getSeriesName,
                    BookMetadata::getSeriesName, BookMetadata::setSeriesName),
            resolved(MetadataField.SERIES_NUMBER, MetadataRefreshOptions.EnabledFields::isSeriesNumber, MetadataRefreshOptions.FieldOptions::getSeriesNumber,
                    BookMetadata::getSeriesNumber, BookMetadata::setSeriesNumber),
            resolvedInteger(MetadataField.SERIES_TOTAL, MetadataRefreshOptions.EnabledFields::isSeriesTotal, MetadataRefreshOptions.FieldOptions::getSeriesTotal,
                    BookMetadata::getSeriesTotal, BookMetadata::setSeriesTotal),
            resolvedString(MetadataField.ISBN_13, MetadataRefreshOptions.EnabledFields::isIsbn13, MetadataRefreshOptions.FieldOptions::getIsbn13,
                    BookMetadata::getIsbn13, BookMetadata::setIsbn13),
            resolvedString(MetadataField.ISBN_10, MetadataRefreshOptions.EnabledFields::isIsbn10, MetadataRefreshOptions.FieldOptions::getIsbn10,
                    BookMetadata::getIsbn10, BookMetadata::setIsbn10),
            resolvedString(MetadataField.LANGUAGE, MetadataRefreshOptions.EnabledFields::isLanguage, MetadataRefreshOptions.FieldOptions::getLanguage,
                    BookMetadata::getLanguage, BookMetadata::setLanguage),
            resolvedInteger(MetadataField.PAGE_COUNT, MetadataRefreshOptions.EnabledFields::isPageCount, MetadataRefreshOptions.FieldOptions::getPageCount,
                    BookMetadata::getPageCount, BookMetadata::setPageCount),
            resolvedString(null, MetadataRefreshOptions.EnabledFields::isCover, MetadataRefreshOptions.FieldOptions::getCover,
                    BookMetadata::getThumbnailUrl, BookMetadata::setThumbnailUrl),
            providerField(MetadataField.AMAZON_RATING, MetadataRefreshOptions.EnabledFields::isAmazonRating, Amazon,
                    BookMetadata::getAmazonRating, BookMetadata::setAmazonRating),
            providerField(MetadataField.AMAZON_REVIEW_COUNT, MetadataRefreshOptions.EnabledFields::isAmazonReviewCount, Amazon,
                    BookMetadata::getAmazonReviewCount, BookMetadata::setAmazonReviewCount),
            providerField(MetadataField.GOODREADS_RATING, MetadataRefreshOptions.EnabledFields::isGoodreadsRating, GoodReads,
                    BookMetadata::getGoodreadsRating, BookMetadata::setGoodreadsRating),
            providerField(MetadataField.GOODREADS_REVIEW_COUNT, MetadataRefreshOptions.EnabledFields::isGoodreadsReviewCount, GoodReads,
                    BookMetadata::getGoodreadsReviewCount, BookMetadata::setGoodreadsReviewCount),
            providerField(MetadataField.HARDCOVER_RATING, MetadataRefreshOptions.EnabledFields::isHardcoverRating, Hardcover,
                    BookMetadata::getHardcoverRating, BookMetadata::setHardcoverRating),
            providerField(MetadataField.HARDCOVER_REVIEW_COUNT, MetadataRefreshOptions.EnabledFields::isHardcoverReviewCount, Hardcover,
                    BookMetadata::getHardcoverReviewCount, BookMetadata::setHardcoverReviewCount),
            providerField(MetadataField.ASIN, MetadataRefreshOptions.EnabledFields::isAsin, Amazon,
                    BookMetadata::getAsin, BookMetadata::setAsin),
            providerField(MetadataField.GOODREADS_ID, MetadataRefreshOptions.EnabledFields::isGoodreadsId, GoodReads,
                    BookMetadata::getGoodreadsId, BookMetadata::setGoodreadsId),
            hardcoverIds(),
            providerField(MetadataField.GOOGLE_ID, MetadataRefreshOptions.EnabledFields::isGoogleId, Google,
                    BookMetadata::getGoogleId, BookMetadata::setGoogleId),
            providerField(MetadataField.COMICVINE_ID, MetadataRefreshOptions.EnabledFields::isComicvineId, Comicvine,
                    BookMetadata::getComicvineId, BookMetadata::setComicvineId),
            providerField(MetadataField.LUBIMYCZYTAC_ID, MetadataRefreshOptions.EnabledFields::isLubimyczytacId, Lubimyczytac,
                    BookMetadata::getLubimyczytacId, BookMetadata::setLubimyczytacId),
            providerField(MetadataField.LUBIMYCZYTAC_RATING, MetadataRefreshOptions.EnabledFields::isLubimyczytacRating, Lubimyczytac,
                    BookMetadata::getLubimyczytacRating, BookMetadata::setLubimyczytacRating),
            providerField(MetadataField.RANOBEDB_ID, MetadataRefreshOptions.EnabledFields::isRanobedbId, Ranobedb,
                    BookMetadata::getRanobedbId, BookMetadata::setRanobedbId),
            providerField(MetadataField.RANOBEDB_RATING, MetadataRefreshOptions.EnabledFields::isRanobedbRating, Ranobedb,
                    BookMetadata::getRanobedbRating, BookMetadata::setRanobedbRating),
            providerField(MetadataField.AUDIBLE_ID, MetadataRefreshOptions.EnabledFields::isAudibleId, Audible,
                    BookMetadata::getAudibleId, BookMetadata::setAudibleId),
            providerField(MetadataField.AUDIBLE_RATING, MetadataRefreshOptions.EnabledFields::isAudibleRating, Audible,
                    BookMetadata::getAudibleRating, BookMetadata::setAudibleRating),
            providerField(MetadataField.AUDIBLE_REVIEW_COUNT, MetadataRefreshOptions.EnabledFields::isAudibleReviewCount, Audible,
                    BookMetadata::getAudibleReviewCount, BookMetadata::setAudibleReviewCount),
            providerField(null, MetadataRefreshOptions.EnabledFields::isMoods, Hardcover,
                    BookMetadata::getMoods, BookMetadata::setMoods),
            providerField(null, MetadataRefreshOptions.EnabledFields::isTags, Hardcover,
                    BookMetadata::getTags, BookMetadata::setTags),
            categories()
    );

    private final List<Function<MetadataRefreshOptions.FieldOptions, MetadataRefreshOptions.FieldProvider>> providerSelectors = List.of(
            MetadataRefreshOptions.FieldOptions::getTitle,
            MetadataRefreshOptions.FieldOptions::getSubtitle,
            MetadataRefreshOptions.FieldOptions::getDescription,
            MetadataRefreshOptions.FieldOptions::getAuthors,
            MetadataRefreshOptions.FieldOptions::getPublisher,
            MetadataRefreshOptions.FieldOptions::getPublishedDate,
            MetadataRefreshOptions.FieldOptions::getSeriesName,
            MetadataRefreshOptions.FieldOptions::getSeriesNumber,
            MetadataRefreshOptions.FieldOptions::getSeriesTotal,
            MetadataRefreshOptions.FieldOptions::getIsbn13,
            MetadataRefreshOptions.FieldOptions::getIsbn10,
            MetadataRefreshOptions.FieldOptions::getLanguage,
            MetadataRefreshOptions.FieldOptions::getCategories,
            MetadataRefreshOptions.FieldOptions::getCover,
            MetadataRefreshOptions.FieldOptions::getPageCount,
            MetadataRefreshOptions.FieldOptions::getAsin,
            MetadataRefreshOptions.FieldOptions::getGoodreadsId,
            MetadataRefreshOptions.FieldOptions::getComicvineId,
            MetadataRefreshOptions.FieldOptions::getHardcoverId,
            MetadataRefreshOptions.FieldOptions::getGoogleId,
            MetadataRefreshOptions.FieldOptions::getLubimyczytacId,
            MetadataRefreshOptions.FieldOptions::getAmazonRating,
            MetadataRefreshOptions.FieldOptions::getAmazonReviewCount,
            MetadataRefreshOptions.FieldOptions::getGoodreadsRating,
            MetadataRefreshOptions.FieldOptions::getGoodreadsReviewCount,
            MetadataRefreshOptions.FieldOptions::getHardcoverRating,
            MetadataRefreshOptions.FieldOptions::getHardcoverReviewCount,
            MetadataRefreshOptions.FieldOptions::getLubimyczytacRating,
            MetadataRefreshOptions.FieldOptions::getRanobedbId,
            MetadataRefreshOptions.FieldOptions::getRanobedbRating,
            MetadataRefreshOptions.FieldOptions::getAudibleId,
            MetadataRefreshOptions.FieldOptions::getAudibleRating,
            MetadataRefreshOptions.FieldOptions::getAudibleReviewCount,
            MetadataRefreshOptions.FieldOptions::getMoods,
            MetadataRefreshOptions.FieldOptions::getTags
    );

    BookMetadata buildFetchMetadata(
            BookMetadata existingMetadata,
            Long bookId,
            MetadataRefreshOptions refreshOptions,
            Map<MetadataProvider, BookMetadata> metadataMap) {
        BookMetadata metadata = BookMetadata.builder().bookId(bookId).build();
        MetadataRefreshOptions.FieldOptions fieldOptions = fieldOptions(refreshOptions);
        MetadataRefreshOptions.EnabledFields enabledFields = enabledFields(refreshOptions);
        MetadataReplaceMode replaceMode = refreshOptions.getReplaceMode();
        boolean isReplaceAll = replaceMode == MetadataReplaceMode.REPLACE_ALL;
        MergeContext context = new MergeContext(fieldOptions, refreshOptions, metadataMap, new EnumMap<>(MetadataField.class));

        for (FieldMergeSpec spec : fieldMergeSpecs) {
            if (spec.enabled().test(enabledFields)) {
                spec.applyFetched().accept(context, metadata);
            } else if (isReplaceAll && existingMetadata != null) {
                // Copying the existing value attributes nothing: the value did not come from this
                // merge, so whatever the field's recorded source already was stays correct.
                spec.copyExisting().accept(metadata, existingMetadata);
            }
        }
        metadata.setFieldProviders(context.capturedProviders());

        BookMetadata comicvine = metadataMap.get(Comicvine);
        if (comicvine != null && comicvine.getComicMetadata() != null) {
            metadata.setComicMetadata(comicvine.getComicMetadata());
        }

        List<BookReview> allReviews = metadataMap.values().stream()
                .filter(Objects::nonNull)
                .flatMap(md -> Optional.ofNullable(md.getBookReviews()).stream().flatMap(Collection::stream))
                .toList();
        if (!allReviews.isEmpty()) {
            metadata.setBookReviews(allReviews);
        }

        if (existingMetadata != null) {
            MetadataLockStatePreserver.copyLockState(existingMetadata, metadata);
            applyExistingFallbacks(existingMetadata, metadata);
        }

        return metadata;
    }

    List<MetadataRefreshOptions.FieldProvider> configuredFieldProviders(MetadataRefreshOptions.FieldOptions fieldOptions) {
        if (fieldOptions == null) {
            return List.of();
        }
        return providerSelectors.stream()
                .map(selector -> selector.apply(fieldOptions))
                .filter(Objects::nonNull)
                .toList();
    }

    <T> T resolveField(
            Map<MetadataProvider, BookMetadata> metadataMap,
            MetadataRefreshOptions.FieldProvider fieldProvider,
            Function<BookMetadata, T> extractor) {
        return resolveFieldWithProviders(metadataMap, fieldProvider, extractor, Objects::nonNull);
    }

    Integer resolveFieldAsInteger(
            Map<MetadataProvider, BookMetadata> metadataMap,
            MetadataRefreshOptions.FieldProvider fieldProvider,
            Function<BookMetadata, Integer> fieldValueExtractor) {
        return resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor, Objects::nonNull);
    }

    String resolveFieldAsString(
            Map<MetadataProvider, BookMetadata> metadataMap,
            MetadataRefreshOptions.FieldProvider fieldProvider,
            FieldValueExtractor fieldValueExtractor) {
        return resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, Objects::nonNull);
    }

    // S1168: null here is a load-bearing sentinel meaning "no provider resolved a value", distinct
    // from "resolved to empty". applyExistingFallbacks() below relies on this null to decide whether
    // to fall back to the existing metadata's value; returning an empty collection instead would make
    // that fallback silently stop firing.
    @SuppressWarnings("java:S1168")
    List<String> resolveFieldAsList(
            Map<MetadataProvider, BookMetadata> metadataMap,
            MetadataRefreshOptions.FieldProvider fieldProvider,
            FieldValueExtractorList fieldValueExtractor) {
        Collection<String> result = resolveFieldWithProviders(
                metadataMap, fieldProvider, fieldValueExtractor::extract, value -> value != null && !value.isEmpty());
        if (result == null) return null;
        return result instanceof List<String> list ? list : new ArrayList<>(result);
    }

    // S1168: see resolveFieldAsList - same null-sentinel contract with applyExistingFallbacks().
    @SuppressWarnings("java:S1168")
    Set<String> resolveFieldAsSet(
            Map<MetadataProvider, BookMetadata> metadataMap,
            MetadataRefreshOptions.FieldProvider fieldProvider,
            FieldValueExtractorList fieldValueExtractor) {
        Collection<String> result = resolveFieldWithProviders(
                metadataMap, fieldProvider, fieldValueExtractor::extract, value -> value != null && !value.isEmpty());
        if (result == null) return null;
        return result instanceof Set<String> set ? set : new HashSet<>(result);
    }

    Set<String> getAllCategories(
            Map<MetadataProvider, BookMetadata> metadataMap,
            MetadataRefreshOptions.FieldProvider fieldProvider,
            FieldValueExtractorList fieldValueExtractor) {
        Set<String> uniqueCategories = new HashSet<>();
        if (fieldProvider == null) {
            return uniqueCategories;
        }

        for (MetadataProvider provider : providers(fieldProvider)) {
            if (provider != null && metadataMap.containsKey(provider)) {
                Collection<String> extracted = fieldValueExtractor.extract(metadataMap.get(provider));
                if (extracted != null) {
                    uniqueCategories.addAll(extracted);
                }
            }
        }

        return uniqueCategories;
    }

    private MetadataRefreshOptions.FieldOptions fieldOptions(MetadataRefreshOptions refreshOptions) {
        MetadataRefreshOptions.FieldOptions fieldOptions = refreshOptions.getFieldOptions();
        return fieldOptions != null ? fieldOptions : new MetadataRefreshOptions.FieldOptions();
    }

    private MetadataRefreshOptions.EnabledFields enabledFields(MetadataRefreshOptions refreshOptions) {
        MetadataRefreshOptions.EnabledFields enabledFields = refreshOptions.getEnabledFields();
        return enabledFields != null ? enabledFields : new MetadataRefreshOptions.EnabledFields();
    }

    private <T> T resolveFieldWithProviders(
            Map<MetadataProvider, BookMetadata> metadataMap,
            MetadataRefreshOptions.FieldProvider fieldProvider,
            Function<BookMetadata, T> extractor,
            Predicate<T> isValidValue) {
        return resolveWithWinner(metadataMap, fieldProvider, extractor, isValidValue).value();
    }

    /**
     * The p1-p4 walk, returning the winning provider alongside its value.
     * <p>
     * Everything else here discards the provider; this is the one place that knows it, so it is the
     * only place provenance can be captured without a second implementation of field priority.
     */
    private <T> Resolved<T> resolveWithWinner(
            Map<MetadataProvider, BookMetadata> metadataMap,
            MetadataRefreshOptions.FieldProvider fieldProvider,
            Function<BookMetadata, T> extractor,
            Predicate<T> isValidValue) {
        if (fieldProvider == null) {
            return new Resolved<>(null, null);
        }
        for (MetadataProvider provider : providers(fieldProvider)) {
            if (provider != null && metadataMap.containsKey(provider)) {
                T value = extractor.apply(metadataMap.get(provider));
                if (isValidValue.test(value)) {
                    return new Resolved<>(value, provider);
                }
            }
        }
        return new Resolved<>(null, null);
    }

    private <T> T resolveAndCapture(
            MergeContext context,
            MetadataField field,
            MetadataRefreshOptions.FieldProvider fieldProvider,
            Function<BookMetadata, T> extractor,
            Predicate<T> isValidValue) {
        Resolved<T> resolved = resolveWithWinner(context.metadataMap(), fieldProvider, extractor, isValidValue);
        context.capture(field, resolved.provider());
        return resolved.value();
    }

    private MetadataProvider[] providers(MetadataRefreshOptions.FieldProvider fieldProvider) {
        return new MetadataProvider[]{
                fieldProvider.getP1(),
                fieldProvider.getP2(),
                fieldProvider.getP3(),
                fieldProvider.getP4()
        };
    }

    private <T> FieldMergeSpec resolved(
            MetadataField field,
            Predicate<MetadataRefreshOptions.EnabledFields> enabled,
            Function<MetadataRefreshOptions.FieldOptions, MetadataRefreshOptions.FieldProvider> providerSelector,
            Function<BookMetadata, T> getter,
            BiConsumer<BookMetadata, T> setter) {
        return new FieldMergeSpec(
                enabled,
                (context, metadata) -> setter.accept(metadata, resolveAndCapture(
                        context, field, providerSelector.apply(context.fieldOptions()), getter, Objects::nonNull)),
                (metadata, existingMetadata) -> setter.accept(metadata, getter.apply(existingMetadata))
        );
    }

    // S4276: ObjIntConsumer<BookMetadata> takes a primitive int and would NPE on unboxing when
    // the resolveFieldAsInteger helper or getter legitimately resolves to null (seriesTotal/pageCount
    // are nullable); BiConsumer<BookMetadata, Integer> is intentional here.
    @SuppressWarnings("java:S4276")
    private FieldMergeSpec resolvedInteger(
            MetadataField field,
            Predicate<MetadataRefreshOptions.EnabledFields> enabled,
            Function<MetadataRefreshOptions.FieldOptions, MetadataRefreshOptions.FieldProvider> providerSelector,
            Function<BookMetadata, Integer> getter,
            BiConsumer<BookMetadata, Integer> setter) {
        return new FieldMergeSpec(
                enabled,
                (context, metadata) -> setter.accept(metadata, resolveAndCapture(
                        context, field, providerSelector.apply(context.fieldOptions()), getter, Objects::nonNull)),
                (metadata, existingMetadata) -> setter.accept(metadata, getter.apply(existingMetadata))
        );
    }

    private FieldMergeSpec resolvedString(
            MetadataField field,
            Predicate<MetadataRefreshOptions.EnabledFields> enabled,
            Function<MetadataRefreshOptions.FieldOptions, MetadataRefreshOptions.FieldProvider> providerSelector,
            FieldValueExtractor getter,
            BiConsumer<BookMetadata, String> setter) {
        return new FieldMergeSpec(
                enabled,
                (context, metadata) -> setter.accept(metadata, resolveAndCapture(
                        context, field, providerSelector.apply(context.fieldOptions()), getter::extract, Objects::nonNull)),
                (metadata, existingMetadata) -> setter.accept(metadata, getter.extract(existingMetadata))
        );
    }

    private FieldMergeSpec resolvedList(
            Predicate<MetadataRefreshOptions.EnabledFields> enabled,
            Function<MetadataRefreshOptions.FieldOptions, MetadataRefreshOptions.FieldProvider> providerSelector,
            FieldValueExtractorList getter,
            BiConsumer<BookMetadata, List<String>> setter) {
        return new FieldMergeSpec(
                enabled,
                (context, metadata) -> setter.accept(metadata,
                        resolveFieldAsList(context.metadataMap(), providerSelector.apply(context.fieldOptions()), getter)),
                (metadata, existingMetadata) -> setter.accept(metadata, asList(getter.extract(existingMetadata)))
        );
    }

    private <T> FieldMergeSpec providerField(
            MetadataField field,
            Predicate<MetadataRefreshOptions.EnabledFields> enabled,
            MetadataProvider provider,
            Function<BookMetadata, T> getter,
            BiConsumer<BookMetadata, T> setter) {
        return new FieldMergeSpec(
                enabled,
                (context, metadata) -> {
                    BookMetadata providerMetadata = context.metadataMap().get(provider);
                    if (providerMetadata != null) {
                        T value = getter.apply(providerMetadata);
                        setter.accept(metadata, value);
                        if (value != null) {
                            context.capture(field, provider);
                        }
                    }
                },
                (metadata, existingMetadata) -> setter.accept(metadata, getter.apply(existingMetadata))
        );
    }

    private FieldMergeSpec hardcoverIds() {
        return new FieldMergeSpec(
                MetadataRefreshOptions.EnabledFields::isHardcoverId,
                (context, metadata) -> {
                    BookMetadata hardcover = context.metadataMap().get(Hardcover);
                    if (hardcover != null) {
                        metadata.setHardcoverId(hardcover.getHardcoverId());
                        metadata.setHardcoverBookId(hardcover.getHardcoverBookId());
                        if (hardcover.getHardcoverId() != null) {
                            context.capture(MetadataField.HARDCOVER_ID, Hardcover);
                        }
                        if (hardcover.getHardcoverBookId() != null) {
                            context.capture(MetadataField.HARDCOVER_BOOK_ID, Hardcover);
                        }
                    }
                },
                (metadata, existingMetadata) -> {
                    metadata.setHardcoverId(existingMetadata.getHardcoverId());
                    metadata.setHardcoverBookId(existingMetadata.getHardcoverBookId());
                }
        );
    }

    private FieldMergeSpec categories() {
        return new FieldMergeSpec(
                MetadataRefreshOptions.EnabledFields::isCategories,
                (context, metadata) -> {
                    if (context.refreshOptions().isMergeCategories()) {
                        metadata.setCategories(getAllCategories(
                                context.metadataMap(), context.fieldOptions().getCategories(), BookMetadata::getCategories));
                    } else {
                        metadata.setCategories(resolveFieldAsSet(
                                context.metadataMap(), context.fieldOptions().getCategories(), BookMetadata::getCategories));
                    }
                },
                (metadata, existingMetadata) -> metadata.setCategories(existingMetadata.getCategories())
        );
    }

    // S1168: preserves the same null-sentinel contract as resolveFieldAsList/resolveFieldAsSet - callers
    // (e.g. applyExistingFallbacks()) distinguish "no existing value" (null) from "existing value is empty".
    @SuppressWarnings("java:S1168")
    private List<String> asList(Collection<String> value) {
        if (value == null) {
            return null;
        }
        return value instanceof List<String> list ? list : new ArrayList<>(value);
    }

    private void applyExistingFallbacks(BookMetadata existingMetadata, BookMetadata metadata) {
        applyExistingDescriptiveFallbacks(existingMetadata, metadata);
        applyExistingSeriesAndIsbnFallbacks(existingMetadata, metadata);
        applyExistingClassificationFallbacks(existingMetadata, metadata);
    }

    private void applyExistingDescriptiveFallbacks(BookMetadata existingMetadata, BookMetadata metadata) {
        if (metadata.getTitle() == null) metadata.setTitle(existingMetadata.getTitle());
        if (metadata.getSubtitle() == null) metadata.setSubtitle(existingMetadata.getSubtitle());
        if (metadata.getDescription() == null) metadata.setDescription(existingMetadata.getDescription());
        if (metadata.getAuthors() == null) metadata.setAuthors(existingMetadata.getAuthors());
        if (metadata.getPublisher() == null) metadata.setPublisher(existingMetadata.getPublisher());
        if (metadata.getPublishedDate() == null) metadata.setPublishedDate(existingMetadata.getPublishedDate());
    }

    private void applyExistingSeriesAndIsbnFallbacks(BookMetadata existingMetadata, BookMetadata metadata) {
        if (metadata.getSeriesName() == null) metadata.setSeriesName(existingMetadata.getSeriesName());
        if (metadata.getSeriesNumber() == null) metadata.setSeriesNumber(existingMetadata.getSeriesNumber());
        if (metadata.getSeriesTotal() == null) metadata.setSeriesTotal(existingMetadata.getSeriesTotal());
        if (metadata.getIsbn13() == null) metadata.setIsbn13(existingMetadata.getIsbn13());
        if (metadata.getIsbn10() == null) metadata.setIsbn10(existingMetadata.getIsbn10());
    }

    private void applyExistingClassificationFallbacks(BookMetadata existingMetadata, BookMetadata metadata) {
        if (metadata.getLanguage() == null) metadata.setLanguage(existingMetadata.getLanguage());
        if (metadata.getPageCount() == null) metadata.setPageCount(existingMetadata.getPageCount());
        if (metadata.getThumbnailUrl() == null) metadata.setThumbnailUrl(existingMetadata.getThumbnailUrl());
        if (metadata.getCategories() == null) metadata.setCategories(existingMetadata.getCategories());
        if (metadata.getMoods() == null) metadata.setMoods(existingMetadata.getMoods());
        if (metadata.getTags() == null) metadata.setTags(existingMetadata.getTags());
    }

    private record MergeContext(
            MetadataRefreshOptions.FieldOptions fieldOptions,
            MetadataRefreshOptions refreshOptions,
            Map<MetadataProvider, BookMetadata> metadataMap,
            Map<MetadataField, MetadataProvider> capturedProviders) {

        /**
         * Files the provider that won a field. A null field is one this merge cannot attribute -
         * a collection or the cover, neither of which the updater writes through a single scalar
         * setter - and a null provider means nothing resolved, which is not something to record.
         */
        void capture(MetadataField field, MetadataProvider provider) {
            if (field != null && provider != null) {
                capturedProviders.put(field, provider);
            }
        }
    }

    private record Resolved<T>(T value, MetadataProvider provider) {
    }

    private record FieldMergeSpec(
            Predicate<MetadataRefreshOptions.EnabledFields> enabled,
            BiConsumer<MergeContext, BookMetadata> applyFetched,
            BiConsumer<BookMetadata, BookMetadata> copyExisting) {
    }
}
