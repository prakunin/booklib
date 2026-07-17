package org.booklore.model.entity;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A book must never hold both a cover hash and a {@code coverProbedAt} marker: the marker records
 * that a probe read the source and found no cover, which a book that now has one contradicts.
 * A dozen call sites stamp a cover hash, so the invariant is enforced in the setter rather than
 * trusted to each of them.
 */
class BookEntityCoverProbeMarkerTest {

    @Nested
    class StampingACoverHash {

        @Test
        void clearsAnExistingProbeMarker() {
            BookEntity book = new BookEntity();
            book.setCoverProbedAt(Instant.parse("2026-01-01T00:00:00Z"));

            book.setBookCoverHash("abc123");

            assertThat(book.getBookCoverHash()).isEqualTo("abc123");
            assertThat(book.getCoverProbedAt()).isNull();
        }

        @Test
        void isHarmlessWhenNoMarkerIsSet() {
            BookEntity book = new BookEntity();

            book.setBookCoverHash("abc123");

            assertThat(book.getBookCoverHash()).isEqualTo("abc123");
            assertThat(book.getCoverProbedAt()).isNull();
        }
    }

    @Nested
    class ClearingTheCoverHash {

        @Test
        void leavesTheProbeMarkerAlone() {
            // Clearing a hash does not disprove an earlier probe, and a rescan is what re-opens the
            // question - so dropping the marker here would silently re-enable the archive re-reads
            // the marker exists to stop.
            BookEntity book = new BookEntity();
            Instant probedAt = Instant.parse("2026-01-01T00:00:00Z");
            book.setCoverProbedAt(probedAt);

            book.setBookCoverHash(null);

            assertThat(book.getBookCoverHash()).isNull();
            assertThat(book.getCoverProbedAt()).isEqualTo(probedAt);
        }
    }
}
