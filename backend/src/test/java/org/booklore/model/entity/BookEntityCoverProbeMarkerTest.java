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

    /**
     * The builder assigns fields directly, so it does not go through the setter and had to be
     * hand-patched to hold the same invariant. Only test fixtures build books with a cover hash
     * today - these pin the route rather than a live defect, because "no caller does this yet" is
     * the reasoning that put the invariant in the setter in the first place.
     */
    @Nested
    class BuildingABook {

        private static final Instant PROBED_AT = Instant.parse("2026-01-01T00:00:00Z");

        @Test
        void cannotSetAMarkerAfterACoverHash() {
            BookEntity book = BookEntity.builder()
                    .bookCoverHash("abc123")
                    .coverProbedAt(PROBED_AT)
                    .build();

            assertThat(book.getBookCoverHash()).isEqualTo("abc123");
            assertThat(book.getCoverProbedAt()).isNull();
        }

        /**
         * The order-reversed case. Patching only the hash setter would leave this one broken, since
         * the marker would simply be assigned afterwards.
         */
        @Test
        void cannotSetACoverHashAfterAMarker() {
            BookEntity book = BookEntity.builder()
                    .coverProbedAt(PROBED_AT)
                    .bookCoverHash("abc123")
                    .build();

            assertThat(book.getBookCoverHash()).isEqualTo("abc123");
            assertThat(book.getCoverProbedAt()).isNull();
        }

        @Test
        void keepsTheMarkerWhenNoCoverHashIsSet() {
            BookEntity book = BookEntity.builder()
                    .coverProbedAt(PROBED_AT)
                    .build();

            assertThat(book.getBookCoverHash()).isNull();
            assertThat(book.getCoverProbedAt()).isEqualTo(PROBED_AT);
        }

        @Test
        void keepsTheMarkerWhenTheCoverHashIsExplicitlyNull() {
            BookEntity book = BookEntity.builder()
                    .bookCoverHash(null)
                    .coverProbedAt(PROBED_AT)
                    .build();

            assertThat(book.getBookCoverHash()).isNull();
            assertThat(book.getCoverProbedAt()).isEqualTo(PROBED_AT);
        }

        /**
         * The hand-written methods must not have displaced the generated ones - Lombok fills the
         * builder in around them, and a typo in a signature would silently produce a second,
         * unused overload while the generated field-assigning one stayed in play.
         */
        @Test
        void stillBuildsEveryOtherFieldNormally() {
            BookEntity book = BookEntity.builder()
                    .id(7L)
                    .bookCoverHash("abc123")
                    .audiobookCoverHash("def456")
                    .build();

            assertThat(book.getId()).isEqualTo(7L);
            assertThat(book.getBookCoverHash()).isEqualTo("abc123");
            assertThat(book.getAudiobookCoverHash()).isEqualTo("def456");
        }
    }
}
