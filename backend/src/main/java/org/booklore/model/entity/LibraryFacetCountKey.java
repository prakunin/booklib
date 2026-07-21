package org.booklore.model.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite id for {@link LibraryFacetCountEntity}: one materialized facet count is uniquely a
 * (library, facet type, facet value) triple.
 */
public class LibraryFacetCountKey implements Serializable {

    private Long libraryId;
    private String facetType;
    private String facetValue;

    public LibraryFacetCountKey() {
    }

    public LibraryFacetCountKey(Long libraryId, String facetType, String facetValue) {
        this.libraryId = libraryId;
        this.facetType = facetType;
        this.facetValue = facetValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LibraryFacetCountKey that)) return false;
        return Objects.equals(libraryId, that.libraryId)
                && Objects.equals(facetType, that.facetType)
                && Objects.equals(facetValue, that.facetValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(libraryId, facetType, facetValue);
    }
}
