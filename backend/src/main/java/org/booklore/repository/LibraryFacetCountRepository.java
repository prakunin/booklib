package org.booklore.repository;

import org.booklore.model.entity.LibraryFacetCountEntity;
import org.booklore.model.entity.LibraryFacetCountKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LibraryFacetCountRepository extends JpaRepository<LibraryFacetCountEntity, LibraryFacetCountKey> {

    /** Projection of a facet value's book count summed across the queried libraries. */
    interface FacetCountSum {
        String getFacetType();

        String getFacetValue();

        Long getBookCount();
    }

    @Query("SELECT f.facetType AS facetType, f.facetValue AS facetValue, SUM(f.bookCount) AS bookCount "
            + "FROM LibraryFacetCountEntity f WHERE f.libraryId IN :libraryIds "
            + "GROUP BY f.facetType, f.facetValue")
    List<FacetCountSum> sumByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    @Query("SELECT f.facetType AS facetType, f.facetValue AS facetValue, SUM(f.bookCount) AS bookCount "
            + "FROM LibraryFacetCountEntity f GROUP BY f.facetType, f.facetValue")
    List<FacetCountSum> sumAllLibraries();

    @Modifying
    @Query("DELETE FROM LibraryFacetCountEntity f WHERE f.libraryId = :libraryId")
    void deleteByLibraryId(@Param("libraryId") Long libraryId);
}
