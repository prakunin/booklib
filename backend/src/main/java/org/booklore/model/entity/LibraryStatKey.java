package org.booklore.model.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite id for {@link LibraryStatEntity}: one materialized statistic is uniquely a
 * (library, stat key) pair.
 */
public class LibraryStatKey implements Serializable {

    private Long libraryId;
    private String statKey;

    public LibraryStatKey() {
    }

    public LibraryStatKey(Long libraryId, String statKey) {
        this.libraryId = libraryId;
        this.statKey = statKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LibraryStatKey that)) return false;
        return Objects.equals(libraryId, that.libraryId)
                && Objects.equals(statKey, that.statKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(libraryId, statKey);
    }
}
