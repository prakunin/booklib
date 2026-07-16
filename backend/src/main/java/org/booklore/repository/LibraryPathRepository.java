package org.booklore.repository;

import org.booklore.model.entity.LibraryPathEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LibraryPathRepository extends JpaRepository<LibraryPathEntity, Long> {
    Optional<LibraryPathEntity> findByLibraryIdAndPath(Long libraryId, String path);

    @Query("SELECT lp FROM LibraryPathEntity lp JOIN FETCH lp.library")
    List<LibraryPathEntity> findAllWithLibrary();

    /**
     * Plain path strings, with no {@code JOIN FETCH} of the owning library. For callers that only
     * ever read the path — unlike {@link #findAllWithLibrary()}, which {@code LibraryHealthService}
     * needs the association for.
     */
    @Query("SELECT lp.path FROM LibraryPathEntity lp")
    List<String> findAllPaths();
}
