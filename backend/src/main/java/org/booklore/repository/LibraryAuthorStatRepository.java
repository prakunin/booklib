package org.booklore.repository;

import org.booklore.model.entity.LibraryAuthorStatEntity;
import org.booklore.model.entity.LibraryAuthorStatKey;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LibraryAuthorStatRepository
        extends JpaRepository<LibraryAuthorStatEntity, LibraryAuthorStatKey> {

    /** An author's global book count and total pages summed across the queried libraries. */
    interface AuthorStatSum {
        Long getAuthorId();

        String getAuthorName();

        Long getBookCount();

        Long getTotalPages();
    }

    // Ordered by book count desc then author name asc to match the live aggregateAuthors ordering, and
    // filtered to >= 2 books (its HAVING clause). Summing over a single-library id list is exact; the
    // read path only calls this for the single-library scope.
    @Query("SELECT s.authorId AS authorId, a.name AS authorName, "
            + "SUM(s.bookCount) AS bookCount, SUM(s.totalPages) AS totalPages "
            + "FROM LibraryAuthorStatEntity s JOIN AuthorEntity a ON a.id = s.authorId "
            + "WHERE s.libraryId IN :libraryIds "
            + "GROUP BY s.authorId, a.name HAVING SUM(s.bookCount) >= 2 "
            + "ORDER BY SUM(s.bookCount) DESC, a.name ASC")
    List<AuthorStatSum> topAuthorsByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    @Modifying
    @Query("DELETE FROM LibraryAuthorStatEntity s WHERE s.libraryId = :libraryId")
    void deleteByLibraryId(@Param("libraryId") Long libraryId);
}
