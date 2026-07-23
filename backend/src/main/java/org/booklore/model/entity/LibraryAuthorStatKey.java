package org.booklore.model.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite id for {@link LibraryAuthorStatEntity}: one row is uniquely a (library, author) pair.
 */
public class LibraryAuthorStatKey implements Serializable {

    private Long libraryId;
    private Long authorId;

    public LibraryAuthorStatKey() {
    }

    public LibraryAuthorStatKey(Long libraryId, Long authorId) {
        this.libraryId = libraryId;
        this.authorId = authorId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LibraryAuthorStatKey that)) return false;
        return Objects.equals(libraryId, that.libraryId)
                && Objects.equals(authorId, that.authorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(libraryId, authorId);
    }
}
