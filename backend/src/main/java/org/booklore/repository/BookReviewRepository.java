package org.booklore.repository;

import org.booklore.model.entity.BookReviewEntity;
import org.booklore.model.enums.MetadataProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface BookReviewRepository extends JpaRepository<BookReviewEntity, Long> {
    List<BookReviewEntity> findByBookMetadataBookId(Long bookId);

    @Modifying
    @Transactional
    @Query("DELETE FROM BookReviewEntity r WHERE r.bookMetadata.book.id = :bookId")
    void deleteByBookMetadataBookId(@Param("bookId") Long bookId);

    long countByMetadataProviderAndBookMetadataBookLibraryId(MetadataProvider metadataProvider, Long libraryId);
}