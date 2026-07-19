package org.booklore.repository;

import org.booklore.model.enums.OpdsSortOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;

public interface BookOpdsRepositoryCustom {

    Page<Long> findBookIds(OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findRecentBookIds(OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByLibraryIds(Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findRecentBookIdsByLibraryIds(Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByShelfId(Long shelfId, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByMetadataSearch(String text, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByMetadataSearchAndLibraryIds(String text, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByMetadataSearchAndShelfIds(String text, Collection<Long> libraryIds, Collection<Long> shelfIds, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByShelfIds(Collection<Long> libraryIds, Collection<Long> shelfIds, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByAuthorName(String authorName, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByAuthorNameAndLibraryIds(String authorName, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsBySeriesName(String seriesName, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsBySeriesNameAndLibraryIds(String seriesName, Collection<Long> libraryIds, OpdsSortOrder sortOrder, Pageable pageable);
}
