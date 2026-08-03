package org.booklore.service.enrichment.work;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkKeysTest {

    @Nested
    class CollapsesNoise {

        @Test
        void ignoresCaseAndSurroundingWhitespace() {
            assertThat(WorkKeys.of("  Булгаков Михаил ", "МАСТЕР И МАРГАРИТА"))
                    .isEqualTo(WorkKeys.of("Булгаков Михаил", "Мастер и Маргарита"));
        }

        @Test
        void ignoresPunctuationAndQuoteStyle() {
            assertThat(WorkKeys.of("Стругацкий, Аркадий", "«Пикник на обочине»"))
                    .isEqualTo(WorkKeys.of("Стругацкий Аркадий", "Пикник на обочине"));
        }

        @Test
        void ignoresDiacritics() {
            assertThat(WorkKeys.of("Hugo Victor", "Les Misérables"))
                    .isEqualTo(WorkKeys.of("Hugo Victor", "Les Miserables"));
        }

        @Test
        void ignoresTrailingEditionQualifiers() {
            String plain = WorkKeys.of("Асприн Роберт", "Ещё один великолепный МИФ");

            assertThat(WorkKeys.of("Асприн Роберт", "Ещё один великолепный МИФ (сборник)")).isEqualTo(plain);
            assertThat(WorkKeys.of("Асприн Роберт", "Ещё один великолепный МИФ том 2")).isEqualTo(plain);
        }
    }

    /**
     * Every rule that widens the key risks copying one work's metadata onto another, so the ones
     * that would are absent on purpose.
     */
    @Nested
    class KeepsWorksApart {

        @Test
        void distinguishesDifferentAuthorsWithTheSameTitle() {
            assertThat(WorkKeys.of("Иванов Иван", "Начало"))
                    .isNotEqualTo(WorkKeys.of("Петров Пётр", "Начало"));
        }

        @Test
        void distinguishesDifferentTitlesBySubtitle() {
            assertThat(WorkKeys.of("Толкин Джон", "Хоббит"))
                    .isNotEqualTo(WorkKeys.of("Толкин Джон", "Хоббит туда и обратно"));
        }

        @Test
        void doesNotStripAQualifierWordFromTheMiddleOfATitle() {
            assertThat(WorkKeys.of("Автор Некий", "Сборник рассказов о море"))
                    .isNotEqualTo(WorkKeys.of("Автор Некий", "рассказов о море"));
        }
    }

    @Nested
    class RefusesToGuess {

        @Test
        void returnsNullWithoutAnAuthor() {
            assertThat(WorkKeys.of(null, "Мастер и Маргарита")).isNull();
            assertThat(WorkKeys.of("  ", "Мастер и Маргарита")).isNull();
        }

        @Test
        void returnsNullWithoutATitle() {
            assertThat(WorkKeys.of("Булгаков Михаил", null)).isNull();
        }

        @Test
        void returnsNullWhenNormalizationLeavesNothing() {
            assertThat(WorkKeys.of("Булгаков Михаил", "...")).isNull();
        }
    }

    @Test
    void staysWithinTheColumnWidth() {
        String key = WorkKeys.of("А".repeat(400), "Б".repeat(400));

        assertThat(key).hasSizeLessThanOrEqualTo(512);
    }
}
