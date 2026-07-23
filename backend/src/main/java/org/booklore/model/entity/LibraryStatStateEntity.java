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
 * Per-library recompute bookkeeping for the materialized statistics. Deliberately separate from
 * {@code library_facet_state}: on an existing installation the facet state is already fresh while the
 * new statistics tables are empty, so sharing one {@code computed_at} would make the sweep skip the
 * empty statistics and the screen would show zeros.
 */
@Entity
@Table(name = "library_stat_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LibraryStatStateEntity {

    @Id
    @Column(name = "library_id")
    private Long libraryId;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;
}
