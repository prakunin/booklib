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
 * Recompute bookkeeping for the whole-catalog materialized statistics. A single-row table keyed on a
 * constant {@code id} (always {@link #SINGLETON_ID}); {@code computed_at} is when the catalog-wide
 * rows were last recomputed.
 */
@Entity
@Table(name = "catalog_stat_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogStatStateEntity {

    /** The one and only row's primary key. */
    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private int id;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;
}
