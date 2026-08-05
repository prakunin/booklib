package org.booklore.repository;

import org.booklore.model.entity.BookMetadataFieldSourceEntity;
import org.booklore.model.enums.MetadataField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BookMetadataFieldSourceRepository
        extends JpaRepository<BookMetadataFieldSourceEntity, BookMetadataFieldSourceEntity.FieldSourceId> {

    List<BookMetadataFieldSourceEntity> findByBookId(Long bookId);

    /**
     * Every row of a whole set of books in one statement. The read path attaches provenance to lists
     * of books, and a per-book lookup there would be a query per row of the page.
     */
    List<BookMetadataFieldSourceEntity> findByBookIdIn(Collection<Long> bookIds);

    void deleteByBookIdAndFieldNameIn(Long bookId, Collection<MetadataField> fieldNames);

    /**
     * Drops the rows for a set of fields across a set of books in one statement.
     * <p>
     * The bulk metadata-management screen changes {@code series_name}, {@code publisher} and
     * {@code language} across whole-library result sets without going through
     * {@code BookMetadataUpdater}, so the rows those writes invalidate have to be removed alongside
     * them. Written as JPQL rather than derived from the method name because Spring Data's derived
     * {@code deleteBy…} loads every matching entity and deletes them one at a time, which on a
     * 702,511-book language merge is 702,511 selects and 702,511 deletes.
     */
    @Modifying
    @Query("DELETE FROM BookMetadataFieldSourceEntity s "
            + "WHERE s.bookId IN :bookIds AND s.fieldName IN :fieldNames")
    void deleteByBookIdInAndFieldNameIn(@Param("bookIds") Collection<Long> bookIds,
                                        @Param("fieldNames") Collection<MetadataField> fieldNames);
}
