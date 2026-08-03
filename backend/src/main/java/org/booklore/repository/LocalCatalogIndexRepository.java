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
