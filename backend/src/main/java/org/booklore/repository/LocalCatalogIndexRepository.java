package org.booklore.repository;

import org.booklore.model.entity.LocalCatalogIndexEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface LocalCatalogIndexRepository extends JpaRepository<LocalCatalogIndexEntity, Long> {

    Optional<LocalCatalogIndexEntity> findByLibraryIdAndSourceTypeAndEntryKey(
            Long libraryId, LocalCatalogSourceType sourceType, String entryKey);

    long countByLibraryIdAndSourceType(Long libraryId, LocalCatalogSourceType sourceType);

    /**
     * Whether the library has any row of this source type at all.
     * <p>
     * Deliberately separate from {@link #countByLibraryIdAndSourceType}, which
     * {@code LocalCatalogStatusService} needs for the numbers it reports. This one answers the
     * yes/no question {@code LocalCatalogIndexBuilder#isIndexed} asks once per enriched book, and
     * the difference is not cosmetic: both queries are served by {@code uk_local_catalog_index} and
     * both are covering, but {@code COUNT(*)} walks every matching index entry while Spring Data's
     * {@code exists} projection emits {@code LIMIT 1} and stops at the first. On the 176,334 REVIEW
     * rows of the dev library that is 42.5 ms against 0.16 ms.
     */
    boolean existsByLibraryIdAndSourceType(Long libraryId, LocalCatalogSourceType sourceType);

    @Modifying
    @Transactional
    @Query("DELETE FROM LocalCatalogIndexEntity i WHERE i.libraryId = :libraryId AND i.sourceType = :sourceType")
    void deleteByLibraryIdAndSourceType(@Param("libraryId") Long libraryId,
                                        @Param("sourceType") LocalCatalogSourceType sourceType);

    @Modifying
    @Transactional
    @Query("DELETE FROM LocalCatalogIndexEntity i WHERE i.libraryId = :libraryId")
    void deleteByLibraryId(@Param("libraryId") Long libraryId);
}
