package org.booklore.service.metadata;

import org.booklore.model.dto.BookMetadata;

final class MetadataLockStatePreserver {

    private MetadataLockStatePreserver() {
    }

    static void copyLockState(BookMetadata source, BookMetadata target) {
        target.setAllMetadataLocked(source.getAllMetadataLocked());
        target.setTitleLocked(source.getTitleLocked());
        target.setSubtitleLocked(source.getSubtitleLocked());
        target.setDescriptionLocked(source.getDescriptionLocked());
        target.setAuthorsLocked(source.getAuthorsLocked());
        target.setPublisherLocked(source.getPublisherLocked());
        target.setPublishedDateLocked(source.getPublishedDateLocked());
        target.setSeriesNameLocked(source.getSeriesNameLocked());
        target.setSeriesNumberLocked(source.getSeriesNumberLocked());
        target.setSeriesTotalLocked(source.getSeriesTotalLocked());
        target.setIsbn13Locked(source.getIsbn13Locked());
        target.setIsbn10Locked(source.getIsbn10Locked());
        target.setPageCountLocked(source.getPageCountLocked());
        target.setLanguageLocked(source.getLanguageLocked());
        target.setCoverLocked(source.getCoverLocked());
        target.setAudiobookCoverLocked(source.getAudiobookCoverLocked());
        target.setAsinLocked(source.getAsinLocked());
        target.setGoodreadsIdLocked(source.getGoodreadsIdLocked());
        target.setComicvineIdLocked(source.getComicvineIdLocked());
        target.setHardcoverIdLocked(source.getHardcoverIdLocked());
        target.setHardcoverBookIdLocked(source.getHardcoverBookIdLocked());
        target.setDoubanIdLocked(source.getDoubanIdLocked());
        target.setGoogleIdLocked(source.getGoogleIdLocked());
        target.setLubimyczytacIdLocked(source.getLubimyczytacIdLocked());
        target.setLubimyczytacRatingLocked(source.getLubimyczytacRatingLocked());
        target.setRanobedbIdLocked(source.getRanobedbIdLocked());
        target.setRanobedbRatingLocked(source.getRanobedbRatingLocked());
        target.setAudibleIdLocked(source.getAudibleIdLocked());
        target.setAudibleRatingLocked(source.getAudibleRatingLocked());
        target.setAudibleReviewCountLocked(source.getAudibleReviewCountLocked());
        target.setAmazonRatingLocked(source.getAmazonRatingLocked());
        target.setAmazonReviewCountLocked(source.getAmazonReviewCountLocked());
        target.setGoodreadsRatingLocked(source.getGoodreadsRatingLocked());
        target.setGoodreadsReviewCountLocked(source.getGoodreadsReviewCountLocked());
        target.setHardcoverRatingLocked(source.getHardcoverRatingLocked());
        target.setHardcoverReviewCountLocked(source.getHardcoverReviewCountLocked());
        target.setDoubanRatingLocked(source.getDoubanRatingLocked());
        target.setDoubanReviewCountLocked(source.getDoubanReviewCountLocked());
        target.setExternalUrlLocked(source.getExternalUrlLocked());
        target.setCategoriesLocked(source.getCategoriesLocked());
        target.setMoodsLocked(source.getMoodsLocked());
        target.setTagsLocked(source.getTagsLocked());
        target.setReviewsLocked(source.getReviewsLocked());
        target.setNarratorLocked(source.getNarratorLocked());
        target.setAbridgedLocked(source.getAbridgedLocked());
        target.setAgeRatingLocked(source.getAgeRatingLocked());
        target.setContentRatingLocked(source.getContentRatingLocked());
    }
}
