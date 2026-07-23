package org.booklore.repository;

import org.booklore.model.entity.LibraryStatEntity;
import org.booklore.model.entity.LibraryStatKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LibraryStatRepository extends JpaRepository<LibraryStatEntity, LibraryStatKey> {

    /** A statistic key's value summed across the queried libraries (for additive keys). */
    interface StatSum {
        String getStatKey();

        Long getStatValue();
    }

    @Query("SELECT s.statKey AS statKey, SUM(s.statValue) AS statValue "
            + "FROM LibraryStatEntity s WHERE s.libraryId IN :libraryIds GROUP BY s.statKey")
    List<StatSum> sumByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    List<LibraryStatEntity> findByLibraryId(Long libraryId);

    @Modifying
    @Query("DELETE FROM LibraryStatEntity s WHERE s.libraryId = :libraryId")
    void deleteByLibraryId(@Param("libraryId") Long libraryId);
}
