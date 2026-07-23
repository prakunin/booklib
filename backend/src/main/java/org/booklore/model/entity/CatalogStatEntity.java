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
 * One materialized whole-catalog scalar statistic. Holds only the non-additive distinct counts
 * ({@code TOTAL_AUTHORS}, {@code TOTAL_SERIES}, {@code TOTAL_PUBLISHERS}) that cannot be summed from
 * the per-library {@link LibraryStatEntity} rows because a single author/series/publisher may span
 * several libraries. Additive counters are summed from the per-library rows and are not stored here.
 * Deliberately has no foreign key to {@code library}: it represents the union of all libraries.
 */
@Entity
@Table(name = "catalog_stat")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogStatEntity {

    @Id
    @Column(name = "stat_key")
    private String statKey;

    @Column(name = "stat_value", nullable = false)
    private long statValue;
}
