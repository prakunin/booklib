package org.booklore.repository;

import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.model.enums.OpdsSortOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

public interface BookOpdsRepositoryCustom {

    default Page<Long> findBookIds(OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIds(sortOrder, pageable, List.of());
    }

    Page<Long> findBookIds(OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findRecentBookIds(OpdsSortOrder sortOrder, Pageable pageable) {
        return findRecentBookIds(sortOrder, pageable, List.of());
    }

    Page<Long> findRecentBookIds(OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsByLibraryIds(Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsByLibraryIds(libraryIds, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsByLibraryIds(Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findRecentBookIdsByLibraryIds(Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable) {
        return findRecentBookIdsByLibraryIds(libraryIds, sortOrder, pageable, List.of());
    }

    Page<Long> findRecentBookIdsByLibraryIds(Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsByShelfId(Long shelfId, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsByShelfId(shelfId, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsByShelfId(Long shelfId, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsByMetadataSearch(String text, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsByMetadataSearch(text, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsByMetadataSearch(String text, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsByMetadataSearchAndLibraryIds(String text, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsByMetadataSearchAndLibraryIds(text, libraryIds, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsByMetadataSearchAndLibraryIds(String text, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsByMetadataSearchAndShelfIds(String text, Collection<Long> libraryIds, Collection<Long> shelfIds, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsByMetadataSearchAndShelfIds(text, libraryIds, shelfIds, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsByMetadataSearchAndShelfIds(String text, Collection<Long> libraryIds, Collection<Long> shelfIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsByShelfIds(Collection<Long> libraryIds, Collection<Long> shelfIds, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsByShelfIds(libraryIds, shelfIds, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsByShelfIds(Collection<Long> libraryIds, Collection<Long> shelfIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsByAuthorName(String authorName, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsByAuthorName(authorName, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsByAuthorName(String authorName, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsByAuthorNameAndLibraryIds(String authorName, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsByAuthorNameAndLibraryIds(authorName, libraryIds, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsByAuthorNameAndLibraryIds(String authorName, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsBySeriesName(String seriesName, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsBySeriesName(seriesName, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsBySeriesName(String seriesName, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    default Page<Long> findBookIdsBySeriesNameAndLibraryIds(String seriesName, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsBySeriesNameAndLibraryIds(seriesName, libraryIds, sortOrder, pageable, List.of());
    }

    Page<Long> findBookIdsBySeriesNameAndLibraryIds(String seriesName, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);

    List<Long> findRandomBookIdsByLibraryIds(Collection<Long> libraryIds, Pageable pageable, Collection<UserContentRestrictionEntity> restrictions);
}
