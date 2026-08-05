package org.booklore.service.enrichment.catalog;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlibustaAuthorKeyTest {

    /**
     * Taken from the live catalog: {@code authors/10.7z} files Daniel Handler's biography under this
     * hash. If the normalization ever drifts, every author lookup silently returns nothing, so the
     * real value is pinned here rather than recomputed by the test.
     */
    private static final String HANDLER = "006018ce8ceae84a232fa485f005c325";

    @Test
    void hashesTheNameAsTheCatalogFilesIt() {
        assertThat(FlibustaAuthorKey.of("Хэндлер Дэниел")).isEqualTo(HANDLER);
    }

    @Test
    void isCaseInsensitive() {
        assertThat(FlibustaAuthorKey.of("ХЭНДЛЕР ДЭНИЕЛ")).isEqualTo(HANDLER);
    }

    @Test
    void collapsesSurroundingAndRepeatedWhitespace() {
        assertThat(FlibustaAuthorKey.of("  Хэндлер   Дэниел  ")).isEqualTo(HANDLER);
    }

    @Test
    void returnsNullForMissingName() {
        assertThat(FlibustaAuthorKey.of(null)).isNull();
        assertThat(FlibustaAuthorKey.of("   ")).isNull();
    }

    /**
     * The catalog files surname first, so given-name-first must produce a different key — that
     * difference is the whole reason a second candidate exists.
     */
    @Test
    void isSensitiveToNameOrder() {
        assertThat(FlibustaAuthorKey.of("Дэниел Хэндлер")).isNotEqualTo(HANDLER);
    }

    /**
     * The candidate list is the measured fix for the 21,689 authors this library stores given-name
     * first. The order is asserted here rather than left to whatever the implementation happens to
     * build, because it decides which biography is preferred when both candidates resolve.
     */
    @Nested
    class Candidates {

        @Test
        void offersTheStoredNameFirstAndTheSurnameFirstRotationSecond() {
            assertThat(FlibustaAuthorKey.candidates("Хэндлер Дэниел"))
                    .containsExactly(HANDLER, FlibustaAuthorKey.of("Дэниел Хэндлер"));
        }

        /**
         * {@code Кевин Митник} is one of the 13,185 two-token authors the measurement found stored
         * given-name first; the catalog files him under {@code Митник Кевин}.
         */
        @Test
        void rotatesATwoTokenGivenNameFirstNameOntoTheCatalogsOrder() {
            assertThat(FlibustaAuthorKey.candidates("Кевин Митник"))
                    .containsExactly(FlibustaAuthorKey.of("Кевин Митник"),
                            FlibustaAuthorKey.of("Митник Кевин"));
        }

        /**
         * A patronymic is not itself a barrier — 21,704 three-token names already match exactly. What
         * breaks is the order: {@code Анатолий Владимирович Афанасьев} is filed as
         * {@code Афанасьев Анатолий Владимирович}, so only the trailing surname moves to the front and
         * the patronymic stays where it is.
         */
        @Test
        void movesOnlyTheTrailingSurnameToTheFrontWhenTheNameCarriesAPatronymic() {
            assertThat(FlibustaAuthorKey.candidates("Анатолий Владимирович Афанасьев"))
                    .containsExactly(FlibustaAuthorKey.of("Анатолий Владимирович Афанасьев"),
                            FlibustaAuthorKey.of("Афанасьев Анатолий Владимирович"));
        }

        /**
         * A name already in the catalog's order still gets its rotation offered — nothing in the stored
         * name says which order it is in — but the rotation of {@code Афанасьев Анатолий Владимирович}
         * is the meaningless {@code Владимирович Афанасьев Анатолий}, which simply matches no key.
         */
        @Test
        void rotatesASurnameFirstNameIntoAKeyThatIsMerelyUnusedRatherThanGuessing() {
            assertThat(FlibustaAuthorKey.candidates("Афанасьев Анатолий Владимирович"))
                    .containsExactly(FlibustaAuthorKey.of("Афанасьев Анатолий Владимирович"),
                            FlibustaAuthorKey.of("Владимирович Афанасьев Анатолий"));
        }

        @Test
        void offersOnlyTheStoredNameForASingleToken() {
            assertThat(FlibustaAuthorKey.candidates("Гомер"))
                    .containsExactly(FlibustaAuthorKey.of("Гомер"));
        }

        @Test
        void normalizesEveryCandidateTheSameWay() {
            assertThat(FlibustaAuthorKey.candidates("  ХЭНДЛЕР   ДЭНИЕЛ "))
                    .isEqualTo(FlibustaAuthorKey.candidates("Хэндлер Дэниел"));
        }

        @Test
        void doesNotRepeatAKeyWhenTheRotationChangesNothing() {
            assertThat(FlibustaAuthorKey.candidates("Иванов Иванов"))
                    .containsExactly(FlibustaAuthorKey.of("Иванов Иванов"));
        }

        @Test
        void offersNoKeysForAMissingName() {
            assertThat(FlibustaAuthorKey.candidates(null)).isEmpty();
            assertThat(FlibustaAuthorKey.candidates("   ")).isEmpty();
        }
    }
}
