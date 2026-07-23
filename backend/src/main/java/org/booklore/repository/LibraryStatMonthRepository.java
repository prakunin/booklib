package org.booklore.repository;

import org.booklore.model.entity.LibraryStatMonthEntity;
import org.booklore.model.entity.LibraryStatMonthKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LibraryStatMonthRepository
        extends JpaRepository<LibraryStatMonthEntity, LibraryStatMonthKey> {

    /** Monthly book counts summed across the queried libraries. */
    interface MonthSum {
        int getYear();

        int getMonth();

        Long getBookCount();
    }

    @Query("SELECT m.year AS year, m.month AS month, SUM(m.bookCount) AS bookCount "
            + "FROM LibraryStatMonthEntity m WHERE m.libraryId IN :libraryIds "
            + "GROUP BY m.year, m.month ORDER BY m.year, m.month")
    List<MonthSum> sumByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    @Modifying
    @Query("DELETE FROM LibraryStatMonthEntity m WHERE m.libraryId = :libraryId")
    void deleteByLibraryId(@Param("libraryId") Long libraryId);
}
