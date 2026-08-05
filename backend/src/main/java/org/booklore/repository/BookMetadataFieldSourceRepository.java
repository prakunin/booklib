package org.booklore.repository;

import org.booklore.model.entity.BookMetadataFieldSourceEntity;
import org.booklore.model.enums.MetadataField;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
