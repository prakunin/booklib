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
 * One materialized scalar statistic for a single library, e.g. {@code TOTAL_BOOKS} or
 * {@code TOTAL_SIZE_KB}. Populated off the request path by the statistics recompute and read back on
 * the statistics screen. Additive counters ({@code TOTAL_BOOKS}, {@code TOTAL_SIZE_KB}) are summed
 * across libraries at read time; non-additive distinct counts ({@code TOTAL_AUTHORS},
 * {@code TOTAL_SERIES}, {@code TOTAL_PUBLISHERS}) are stored here per library for the single-library
 * scope and separately in {@link CatalogStatEntity} for the whole-catalog scope.
 */
@Entity
@Table(name = "library_stat")
@IdClass(LibraryStatKey.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LibraryStatEntity {

    @Id
    @Column(name = "library_id")
    private Long libraryId;

    @Id
    @Column(name = "stat_key")
    private String statKey;

    @Column(name = "stat_value", nullable = false)
    private long statValue;
}
