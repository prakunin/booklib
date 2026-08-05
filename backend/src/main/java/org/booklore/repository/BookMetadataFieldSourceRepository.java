package org.booklore.repository;

import org.booklore.model.entity.BookMetadataFieldSourceEntity;
import org.booklore.model.enums.MetadataField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BookMetadataFieldSourceRepository
        extends JpaRepository<BookMetadataFieldSourceEntity, BookMetadataFieldSourceEntity.FieldSourceId> {

    List<BookMetadataFieldSourceEntity> findByBookId(Long bookId);

    void deleteByBookIdAndFieldNameIn(Long bookId, Collection<MetadataField> fieldNames);
}
