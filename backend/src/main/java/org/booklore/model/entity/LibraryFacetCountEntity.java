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
 * One materialized facet count for a single library: how many books in {@code library_id} have the
 * value {@code facet_value} for the facet {@code facet_type}. Populated off the request path by the
 * facet-count recompute task and summed across libraries at read time (a book belongs to exactly one
 * library, so per-library facet counts are additive).
 */
@Entity
@Table(name = "library_facet_count")
@IdClass(LibraryFacetCountKey.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LibraryFacetCountEntity {

    @Id
    @Column(name = "library_id")
    private Long libraryId;

    @Id
    @Column(name = "facet_type")
    private String facetType;

    @Id
    @Column(name = "facet_value")
    private String facetValue;

    @Column(name = "book_count", nullable = false)
    private long bookCount;
}
