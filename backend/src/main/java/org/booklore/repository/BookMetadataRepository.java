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
    /**
     * Rows whose stored {@code search_text} still carries typographic punctuation, in book-id order
     * for keyset paging. Only the two columns the backfill touches are read: rebuilding the text
     * from metadata instead would have to load every author of every book.
     */
    @Query("""
            SELECT m.bookId AS bookId, m.searchText AS searchText FROM BookMetadataEntity m
            WHERE m.bookId > :afterBookId
            AND (m.searchText LIKE '%\u00AB%' OR m.searchText LIKE '%\u00BB%'
              OR m.searchText LIKE '%\u201C%' OR m.searchText LIKE '%\u201D%'
              OR m.searchText LIKE '%\u201E%' OR m.searchText LIKE '%\u201F%'
              OR m.searchText LIKE '%\u2039%' OR m.searchText LIKE '%\u203A%'
              OR m.searchText LIKE '%\u2018%' OR m.searchText LIKE '%\u2019%'
              OR m.searchText LIKE '%\u201A%' OR m.searchText LIKE '%\u201B%'
              OR m.searchText LIKE '%\u2010%' OR m.searchText LIKE '%\u2011%'
              OR m.searchText LIKE '%\u2012%' OR m.searchText LIKE '%\u2013%'
              OR m.searchText LIKE '%\u2014%' OR m.searchText LIKE '%\u2015%'
              OR m.searchText LIKE '%\u2212%')
            ORDER BY m.bookId
            """)
    List<SearchTextView> findSearchTextsWithTypographicPunctuation(@Param("afterBookId") long afterBookId,
                                                                   Pageable pageable);

    /**
     * Writes the folded text directly, bypassing the {@code @PreUpdate} hook that would otherwise
     * rebuild {@code search_text} from the metadata fields and their lazily loaded authors.
     */
    @Modifying
    @Query("UPDATE BookMetadataEntity m SET m.searchText = :searchText WHERE m.bookId = :bookId")
    void updateSearchText(@Param("bookId") Long bookId, @Param("searchText") String searchText);

    interface SearchTextView {
        Long getBookId();

        String getSearchText();
    }

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
