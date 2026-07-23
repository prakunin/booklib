package org.booklore.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CatalogAuthorStatRepository extends JpaRepository<org.booklore.model.entity.CatalogAuthorStatEntity, Long> {

    /** An author's global book count, total pages and display name across the whole catalog. */
    interface AuthorStatRow {
        Long getAuthorId();

        String getAuthorName();

        Long getBookCount();

        Long getTotalPages();
    }

    // Ordered by book count desc then author name asc to match the live aggregateAuthors ordering, and
    // filtered to >= 2 books (its HAVING clause).
    @Query("SELECT s.authorId AS authorId, a.name AS authorName, "
            + "s.bookCount AS bookCount, s.totalPages AS totalPages "
            + "FROM CatalogAuthorStatEntity s JOIN AuthorEntity a ON a.id = s.authorId "
            + "WHERE s.bookCount >= 2 ORDER BY s.bookCount DESC, a.name ASC")
    List<AuthorStatRow> topAuthors(Pageable pageable);
}
