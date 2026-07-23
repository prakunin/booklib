package org.booklore.model.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite id for {@link LibraryStatMonthEntity}: one row is uniquely a (library, year, month) triple.
 */
public class LibraryStatMonthKey implements Serializable {

    private Long libraryId;
    private int year;
    private int month;

    public LibraryStatMonthKey() {
    }

    public LibraryStatMonthKey(Long libraryId, int year, int month) {
        this.libraryId = libraryId;
        this.year = year;
        this.month = month;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LibraryStatMonthKey that)) return false;
        return year == that.year
                && month == that.month
                && Objects.equals(libraryId, that.libraryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(libraryId, year, month);
    }
}
