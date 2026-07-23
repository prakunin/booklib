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
 * Materialized count of books added to a single library in a given calendar (year, month). Backs the
 * {@code booksAddedByMonth} series on the statistics screen; additive across libraries at read time.
 */
@Entity
@Table(name = "library_stat_month")
@IdClass(LibraryStatMonthKey.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LibraryStatMonthEntity {

    @Id
    @Column(name = "library_id")
    private Long libraryId;

    @Id
    @Column(name = "year")
    private int year;

    @Id
    @Column(name = "month")
    private int month;

    @Column(name = "book_count", nullable = false)
    private long bookCount;
}
