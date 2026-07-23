package org.booklore.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Materialized global part of an author's statistics across the whole catalog: total books and total
 * pages over all libraries. Holds the top {@code FACET_LIMIT} authors of the catalog, used to serve
 * the "all libraries" scope exactly without merging truncated per-library lists.
 */
@Entity
@Table(name = "catalog_author_stat")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogAuthorStatEntity {

    @Id
    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "book_count", nullable = false)
    private long bookCount;

    @Column(name = "total_pages", nullable = false)
    private long totalPages;
}
