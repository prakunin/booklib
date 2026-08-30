package org.booklore.repository;

import org.booklore.model.entity.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface BookMetadataRepository extends JpaRepository<BookMetadataEntity, Long> {

    @Query("SELECT m FROM BookMetadataEntity m WHERE m.bookId IN :bookIds")
    List<BookMetadataEntity> getMetadataForBookIds(@Param("bookIds") List<Long> bookIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BookMetadataEntity m SET m.coverUpdatedOn = :timestamp WHERE m.bookId = :bookId")
    void updateCoverTimestamp(@Param("bookId") Long bookId, @Param("timestamp") Instant timestamp);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BookMetadataEntity m SET m.audiobookCoverUpdatedOn = :timestamp WHERE m.bookId = :bookId")
    void updateAudiobookCoverTimestamp(@Param("bookId") Long bookId, @Param("timestamp") Instant timestamp);

    /**
     * Unlocked, non-empty subtitles in book-id order, for the repair pass over values written by the
     * earlier, looser title-page heuristic. Keyset paging rather than an offset: the pass rewrites
     * rows as it goes, and an offset would step over the ones it just changed.
     */
    @Query("""
            SELECT m FROM BookMetadataEntity m
            WHERE m.bookId > :afterBookId
            AND m.subtitle IS NOT NULL
            AND m.subtitle <> ''
            AND (m.subtitleLocked IS NULL OR m.subtitleLocked = false)
            ORDER BY m.bookId
            """)
    List<BookMetadataEntity> findUnlockedSubtitlesAfterBookId(@Param("afterBookId") long afterBookId,
                                                              Pageable pageable);

    /**
     * Rows whose title carries the replacement character at all. The cheap SQL predicate only
     * narrows the field; whether a title is genuinely undecodable is decided in Java, where the
     * ratio test lives.
     */
    @Query("""
            SELECT m FROM BookMetadataEntity m
            WHERE m.bookId > :afterBookId
            AND m.title LIKE CONCAT('%', :replacementCharacter, '%')
            AND (m.titleLocked IS NULL OR m.titleLocked = false)
            ORDER BY m.bookId
            """)
    List<BookMetadataEntity> findTitlesContainingAfterBookId(@Param("afterBookId") long afterBookId,
                                                             @Param("replacementCharacter") String replacementCharacter,
                                                             Pageable pageable);

    List<BookMetadataEntity> findAllByAuthorsContaining(AuthorEntity author);

    List<BookMetadataEntity> findAllByCategoriesContaining(CategoryEntity category);

    List<BookMetadataEntity> findAllByMoodsContaining(MoodEntity mood);

    List<BookMetadataEntity> findAllByTagsContaining(TagEntity tag);

    List<BookMetadataEntity> findAllBySeriesNameIgnoreCase(String seriesName);

    List<BookMetadataEntity> findAllByPublisherIgnoreCase(String publisher);

    List<BookMetadataEntity> findAllByLanguageIgnoreCase(String language);
}
