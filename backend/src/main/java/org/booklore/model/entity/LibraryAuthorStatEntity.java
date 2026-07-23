package org.booklore.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Materialized global part of an author's statistics within a single library: how many books the
 * author has there and their total page count. Holds the top {@code FACET_LIMIT} authors of the
 * library. The per-user part (average rating, read count) is not materialized; it is fetched live for
 * the selected authors at read time. Keyed on {@code author_id} (stable) so the display name is joined
 * from {@code author} when read.
 */
@Entity
@Table(name = "library_author_stat")
@IdClass(LibraryAuthorStatKey.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LibraryAuthorStatEntity {

    @Id
    @Column(name = "library_id")
    private Long libraryId;

    @Id
    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "book_count", nullable = false)
    private long bookCount;

    @Column(name = "total_pages", nullable = false)
    private long totalPages;
}
