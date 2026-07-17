package org.booklore.repository;

import jakarta.persistence.QueryHint;
import org.booklore.model.entity.BookFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookFileRepository extends JpaRepository<BookFileEntity, Long> {

    // Deliberately queries the raw columns (not CONCAT(...)) so MariaDB can use the
    // idx_book_file_archive_source composite index; wrapping both columns in CONCAT made the
    // predicate non-sargable, forcing a full scan of the library's book_file rows on every
    // one of the ~842 batches a 421k-book INPX index produces. The archives/entries cross
    // product can over-fetch relative to the batch's own keys, but that is harmless at the
    // batch size used here (500), since a batch's records come from a handful of archives.
    @Query("""
            SELECT bf.sourceArchive, bf.sourceArchiveEntry
            FROM BookFileEntity bf
            WHERE bf.book.library.id = :libraryId
            AND bf.sourceArchive IN :archives
            AND bf.sourceArchiveEntry IN :entries
            """)
    List<Object[]> findExistingArchiveEntries(@Param("libraryId") Long libraryId,
                                               @Param("archives") Collection<String> archives,
                                               @Param("entries") Collection<String> entries);

    @Query("""
            SELECT bf
            FROM BookFileEntity bf
            WHERE bf.book.library.id = :libraryId
            AND bf.fileSizeKb IS NULL
            AND bf.sourceArchive IN :archives
            AND bf.sourceArchiveEntry IN :entries
            """)
    List<BookFileEntity> findArchiveEntriesMissingSize(@Param("libraryId") Long libraryId,
                                                        @Param("archives") Collection<String> archives,
                                                        @Param("entries") Collection<String> entries);

    @EntityGraph(attributePaths = {"book", "book.library", "book.libraryPath"})
    @Query("""
            SELECT bf
            FROM BookFileEntity bf
            JOIN bf.book b
            WHERE bf.id > :afterId
            AND bf.isBookFormat = true
            AND bf.fileSizeKb IS NULL
            AND bf.sourceArchive IS NOT NULL
            AND bf.sourceArchive <> ''
            AND bf.sourceArchiveEntry IS NOT NULL
            AND bf.sourceArchiveEntry <> ''
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY bf.id
            """)
    List<BookFileEntity> findArchivedBookFilesMissingSizeAfterId(@Param("afterId") long afterId, Pageable pageable);

    @Query("""
            SELECT bf.sourceArchive, COUNT(bf)
            FROM BookFileEntity bf
            WHERE bf.book.library.id = :libraryId
            AND bf.sourceArchive IS NOT NULL
            GROUP BY bf.sourceArchive
            """)
    List<Object[]> countArchiveEntriesByLibraryId(@Param("libraryId") Long libraryId);

    // Full grouped aggregation over the library's book_file rows; bounded by a per-query timeout
    // (tighter than the global hibernate.query.timeout) so a pathologically slow run cannot hold a
    // pooled connection for the full global timeout. Results are cached in InpxArchiveCatalogService.
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "20000"))
    @Query("""
            SELECT bf.sourceArchive, COUNT(bf), MIN(b.addedOn), MAX(b.scannedOn),
                   SUM(CASE WHEN b.bookCoverHash IS NOT NULL THEN 1 ELSE 0 END)
            FROM BookFileEntity bf
            JOIN bf.book b
            WHERE b.library.id = :libraryId
            AND bf.sourceArchive IS NOT NULL
            GROUP BY bf.sourceArchive
            """)
    List<Object[]> findArchiveStatistics(@Param("libraryId") Long libraryId);

    @Query("""
            SELECT b.id
            FROM BookFileEntity bf
            JOIN bf.book b
            WHERE b.library.id = :libraryId
            AND bf.sourceArchive = :archiveName
            ORDER BY b.id
            """)
    List<Long> findBookIdsByArchive(@Param("libraryId") Long libraryId,
                                    @Param("archiveName") String archiveName);

    @Query("""
            SELECT bf FROM BookFileEntity bf
            WHERE bf.book.libraryPath.id = :libraryPathId
            AND bf.fileSubPath = :fileSubPath
            AND bf.fileName = :fileName
            """)
    Optional<BookFileEntity> findByLibraryPathIdAndFileSubPathAndFileName(
            @Param("libraryPathId") Long libraryPathId,
            @Param("fileSubPath") String fileSubPath,
            @Param("fileName") String fileName);

    @Query("SELECT COUNT(bf) FROM BookFileEntity bf WHERE bf.book.id = :bookId")
    long countByBookId(@Param("bookId") Long bookId);

    @Modifying
    @Transactional
    @Query("UPDATE BookFileEntity bf SET bf.book.id = :targetBookId WHERE bf.id IN :fileIds")
    void reassignFilesToBook(@Param("targetBookId") Long targetBookId, @Param("fileIds") List<Long> fileIds);

    @Modifying
    @Transactional
    @Query("UPDATE BookFileEntity bf SET bf.book.id = :targetBookId, bf.fileSubPath = :fileSubPath WHERE bf.id = :fileId")
    void reassignFileToBookWithPath(@Param("targetBookId") Long targetBookId, @Param("fileSubPath") String fileSubPath, @Param("fileId") Long fileId);

    @Query("""
            SELECT bf FROM BookFileEntity bf
            LEFT JOIN FETCH bf.book b
            LEFT JOIN FETCH b.libraryPath
            LEFT JOIN FETCH b.library
            WHERE bf.id = :id
            """)
    Optional<BookFileEntity> findByIdWithBookAndLibraryPath(@Param("id") Long id);
}
