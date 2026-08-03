package org.booklore.service.metadata;

import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.BookReview;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.BookReviewEntity;
import org.booklore.model.enums.MetadataProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BookReviewUpdateService {

    public void addReviewsToBook(List<BookReview> bookReviews, BookMetadataEntity e) {
        BookMetadata tempMetadata = BookMetadata.builder()
                .bookReviews(bookReviews)
                .build();
        MetadataClearFlags clearFlags = new MetadataClearFlags();
        updateBookReviews(tempMetadata, e, clearFlags, true);
    }

    public void updateBookReviews(BookMetadata metadata, BookMetadataEntity entity, MetadataClearFlags clearFlags, boolean mergeWithExisting) {
        if (Boolean.TRUE.equals(entity.getReviewsLocked())) {
            return;
        }
        if (clearFlags.isReviews()) {
            entity.getReviews().clear();
            return;
        }
        if (!isFieldUpdateAllowed(false, metadata.getBookReviews()) || metadata.getBookReviews() == null) {
            return;
        }
        if (mergeWithExisting) {
            addReviewsToEntity(metadata.getBookReviews(), entity);
        } else {
            replaceReviewsInEntity(metadata.getBookReviews(), entity);
        }

        applyReviewLimitsAndUpdate(entity);
    }

    /**
     * A provider owns its own reviews entirely, so incoming reviews replace that provider's previous
     * ones rather than joining them. Appending cannot work: {@code BookReviewEntity} has no value
     * equality, so the backing {@code Set} does not dedupe, and a repeated run would leave the
     * per-provider cap filled with copies of the same review.
     */
    private void addReviewsToEntity(List<BookReview> reviews, BookMetadataEntity entity) {
        Set<MetadataProvider> incomingProviders = reviews.stream()
                .filter(review -> review != null && review.getMetadataProvider() != null)
                .map(BookReview::getMetadataProvider)
                .collect(Collectors.toSet());
        entity.getReviews().removeIf(existing -> incomingProviders.contains(existing.getMetadataProvider()));
        for (var review : reviews) {
            if (review == null || review.getMetadataProvider() == null) continue;
            BookReviewEntity reviewEntity = createReviewEntity(review, entity);
            entity.getReviews().add(reviewEntity);
        }
    }

    private void replaceReviewsInEntity(List<BookReview> reviews, BookMetadataEntity entity) {
        entity.getReviews().clear();
        Set<BookReviewEntity> newReviews = reviews.stream()
                .filter(review -> review != null && review.getMetadataProvider() != null)
                .map(review -> createReviewEntity(review, entity))
                .collect(Collectors.toSet());
        entity.getReviews().addAll(newReviews);
    }

    private static String truncate(String input, int maxLength) {
        if (input == null) return null;
        return input.length() <= maxLength ? input : input.substring(0, maxLength);
    }

    private BookReviewEntity createReviewEntity(BookReview review, BookMetadataEntity entity) {
        // Some fields are truncated to properly fit in the entity field's max length.
        return BookReviewEntity.builder()
                .bookMetadata(entity)
                .metadataProvider(review.getMetadataProvider())
                .reviewerName(truncate(review.getReviewerName(), 512))
                .title(truncate(review.getTitle(), 512))
                .rating(review.getRating())
                .date(review.getDate())
                .body(review.getBody())
                .spoiler(review.getSpoiler())
                .followersCount(review.getFollowersCount())
                .textReviewsCount(review.getTextReviewsCount())
                .country(truncate(review.getCountry(), 512))
                .build();
    }

    private void applyReviewLimitsAndUpdate(BookMetadataEntity entity) {
        Set<BookReviewEntity> limitedReviews = applyReviewLimitsPerProvider(entity.getReviews());
        entity.getReviews().clear();
        entity.getReviews().addAll(limitedReviews);
    }

    private Set<BookReviewEntity> applyReviewLimitsPerProvider(Set<BookReviewEntity> reviews) {
        return reviews.stream()
                .collect(Collectors.groupingBy(BookReviewEntity::getMetadataProvider))
                .entrySet()
                .stream()
                .flatMap(entry -> entry.getValue().stream()
                        .sorted(Comparator.comparing(BookReviewEntity::getDate,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(5))
                .collect(Collectors.toSet());
    }

    private boolean isFieldUpdateAllowed(Boolean isLocked, Object fieldValue) {
        return (isLocked == null || !isLocked) && fieldValue != null;
    }
}
