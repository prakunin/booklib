package org.booklore.model;

import org.booklore.model.enums.CoverProbeOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the states a {@link CoverExtraction} cannot hold.
 * <p>
 * These used to be conventions enforced at the far end of the call chain: {@code BookCoverService}
 * checked at runtime whether a {@code COVER_FOUND} actually carried bytes, because the interface's
 * old default produced exactly that - a cover reported by a processor that had already written the
 * image itself and had nothing to hand back. That check ran after the write it existed to prevent,
 * and refusing at that point left the book eligible, so the next scan re-read and re-wrote it. The
 * value is now unconstructible and every processor reads without writing, which is what lets that
 * guard be deleted rather than merely fixed.
 */
class CoverExtractionTest {

    @Nested
    class CoverFound {

        @Test
        void carriesTheBytesItWasGiven() {
            byte[] data = {1, 2, 3};

            CoverExtraction extraction = CoverExtraction.found(data);

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.COVER_FOUND);
            assertThat(extraction.data()).isEqualTo(data);
        }

        /**
         * The state the deleted runtime guard used to catch: a cover reported with no bytes behind
         * it. Constructing it directly is the only route left, and it must not be one.
         */
        @Test
        void cannotBeConstructedWithoutBytes() {
            assertThatThrownBy(() -> new CoverExtraction(CoverProbeOutcome.COVER_FOUND, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("COVER_FOUND requires cover bytes");
        }

        @Test
        void cannotBeConstructedWithEmptyBytes() {
            assertThatThrownBy(() -> CoverExtraction.found(new byte[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("COVER_FOUND requires cover bytes");
        }
    }

    @Nested
    class OutcomesWithoutACover {

        @Test
        void noCoverFoundCarriesNoBytes() {
            CoverExtraction extraction = CoverExtraction.noCoverFound();

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.NO_COVER_FOUND);
            assertThat(extraction.data()).isNull();
        }

        @Test
        void readFailedCarriesNoBytes() {
            CoverExtraction extraction = CoverExtraction.readFailed();

            assertThat(extraction.outcome()).isEqualTo(CoverProbeOutcome.READ_FAILED);
            assertThat(extraction.data()).isNull();
        }

        /**
         * A caller switching on the outcome must be able to trust it. Bytes attached to a "there is
         * no cover" answer would mean the two disagree, and whichever the caller read would be
         * arbitrary.
         */
        @Test
        void cannotCarryBytesAlongsideANegativeOutcome() {
            assertThatThrownBy(() -> new CoverExtraction(CoverProbeOutcome.NO_COVER_FOUND, new byte[]{1}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot carry cover bytes");
            assertThatThrownBy(() -> new CoverExtraction(CoverProbeOutcome.READ_FAILED, new byte[]{1}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot carry cover bytes");
        }
    }
}
