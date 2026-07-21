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

import java.time.Instant;

/**
 * Per-library recompute bookkeeping for the materialized facet counts. {@code computedAt} is when the
 * library's {@link LibraryFacetCountEntity} rows were last recomputed; the dirty-flag sweep recomputes
 * a library only when its books have changed since then.
 */
@Entity
@Table(name = "library_facet_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LibraryFacetStateEntity {

    @Id
    @Column(name = "library_id")
    private Long libraryId;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;
}
