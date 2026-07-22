package org.booklore.repository;

import org.booklore.model.entity.AuthorEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface AuthorRepository extends JpaRepository<AuthorEntity, Long> {

    Optional<AuthorEntity> findByName(String name);

    Optional<AuthorEntity> findByNameIgnoreCase(String name);

    @Query("SELECT a FROM AuthorEntity a JOIN a.bookMetadataEntityList bm WHERE bm.bookId = :bookId")
    List<AuthorEntity> findAuthorsByBookId(@Param("bookId") Long bookId);

    Optional<AuthorEntity> findByAsin(String asin);

    @Query("SELECT a.id AS id, a.name AS name FROM AuthorEntity a " +
           "WHERE (a.normalizedName IS NULL OR NOT EXISTS " +
           "(SELECT 1 FROM AuthorReconcileStateEntity s WHERE s.authorId = a.id)) " +
           "AND a.id > :lastId ORDER BY a.id")
    List<AuthorBackfillView> findAuthorsNeedingBackfillAfter(@Param("lastId") Long lastId, Pageable pageable);

    @Modifying
    @Query("UPDATE AuthorEntity a SET a.normalizedName = :nn WHERE a.id = :id AND a.normalizedName IS NULL")
    int backfillNormalizedName(@Param("id") Long id, @Param("nn") String nn);

    @Query("SELECT a, COUNT(bm) FROM AuthorEntity a LEFT JOIN a.bookMetadataEntityList bm GROUP BY a ORDER BY a.name")
    List<Object[]> findAllWithBookCount();

    @Query("SELECT a, COUNT(DISTINCT bm) FROM AuthorEntity a LEFT JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE b.library.id IN :libraryIds GROUP BY a ORDER BY a.name")
    List<Object[]> findAllWithBookCountByLibraryIds(@Param("libraryIds") Set<Long> libraryIds);

    @Query("SELECT COUNT(b) > 0 FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE a.id = :authorId AND b.library.id IN :libraryIds")
    boolean existsByIdAndLibraryIds(@Param("authorId") Long authorId, @Param("libraryIds") Set<Long> libraryIds);

    @Query("SELECT bm.bookId AS bookId, a.name AS authorName FROM AuthorEntity a JOIN a.bookMetadataEntityList bm WHERE bm.bookId IN :bookIds ORDER BY a.name")
    List<AuthorBookProjection> findAuthorNamesByBookIds(@Param("bookIds") Set<Long> bookIds);

    interface AuthorBookProjection {
        Long getBookId();
        String getAuthorName();
    }

    interface AuthorBackfillView {
        Long getId();
        String getName();
    }
}
