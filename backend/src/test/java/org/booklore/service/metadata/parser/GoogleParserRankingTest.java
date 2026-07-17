package org.booklore.service.metadata.parser;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ranking's job is to order results by how well each answers the query that produced it.
 * Nothing here may drop or duplicate a result: langRestrict already filters, and a filter can make
 * a mis-tagged edition vanish outright, which is why the language signal is a boost and lives here.
 */
class GoogleParserRankingTest {

    private BookMetadata result(String title, String language, String isbn13, String publisher) {
        return BookMetadata.builder()
                .title(title)
                .language(language)
                .isbn13(isbn13)
                .publisher(publisher)
                .build();
    }

    private GoogleParser.RankingContext context(String query, boolean isIsbnSearch, String bookLanguage) {
        return new GoogleParser.RankingContext(query, isIsbnSearch, bookLanguage);
    }

    @Nested
    class IsbnSearches {

        @Test
        void putsTheExactIsbnMatchFirstEvenWhenItHasFewerFields() {
            BookMetadata sparseButExact = result("Right book", null, "9785171147426", null);
            BookMetadata richButWrong = result("Wrong book", "en", "9780000000000", "Some Publisher");

            // Production shape: getMetadataListByIsbn feeds rank() a query prefixed with "isbn:".
            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(richButWrong, sparseButExact),
                    context("isbn:9785171147426", true, null));

            assertThat(ranked).hasSize(2).containsExactly(sparseButExact, richButWrong);
        }

        @Test
        void stripsTheIsbnPrefixAndHyphensWhenMatchingTheIsbn() {
            BookMetadata exact = result("Right book", null, "9785171147426", null);
            BookMetadata other = result("Other book", "en", "9780000000000", "Publisher");

            // Still the "isbn:" shape, and the ISBN itself carries hyphens that must also be stripped.
            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(other, exact),
                    context("isbn:978-5-17-114742-6", true, null));

            assertThat(ranked).hasSize(2).containsExactly(exact, other);
        }

        @Test
        void doesNotApplyTheIsbnSignalWhenTheSearchWasNotAnIsbnSearch() {
            BookMetadata sparseWithThatIsbn = result("Sparse", null, "9785171147426", null);
            BookMetadata complete = result("Complete", null, "9780000000000", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(sparseWithThatIsbn, complete),
                    context("9785171147426", false, null));

            assertThat(ranked).hasSize(2).containsExactly(complete, sparseWithThatIsbn);
        }
    }

    @Nested
    class BookLanguageSignal {

        @Test
        void ranksTheResultMatchingTheBooksLanguageAboveAnEqualCompletenessResultInAnotherLanguage() {
            BookMetadata english = result("Hyperion", "en", "9780000000001", "Publisher");
            BookMetadata russian = result("Гиперион", "ru", "9780000000002", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(english, russian),
                    context("Гиперион Симмонс", false, "ru"));

            assertThat(ranked).hasSize(2).containsExactly(russian, english);
        }

        @Test
        void ranksTheUkrainianOriginalAboveARussianTranslationWhenTheBooksLanguageIsUkrainian() {
            // Cyrillic script is shared by Ukrainian and Russian; the boost must come from the
            // book's own recorded language, never from guessing at the query's script.
            BookMetadata ukrainian = result("Кобзар", "uk", "9780000000001", null);
            BookMetadata russian = result("Кобзарь", "ru", "9780000000002", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(russian, ukrainian),
                    context("Кобзар Шевченко", false, "uk"));

            assertThat(ranked).hasSize(2).containsExactly(ukrainian, russian);
        }

        @Test
        void aResultWithNoRecordedLanguageReceivesNoBoost() {
            BookMetadata russian = result("Гиперион", "ru", "9780000000001", null);
            BookMetadata untaggedButComplete = result("Гиперион", null, "9780000000002", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(untaggedButComplete, russian),
                    context("Гиперион", false, "ru"));

            // The Russian-tagged result gets the boost; the untagged one is judged on completeness
            // alone and still ranks below it despite being the more complete record.
            assertThat(ranked).hasSize(2).containsExactly(russian, untaggedButComplete);
        }

        @Test
        void treatsRuAndRusAndRuDashRuAsTheSameBookLanguage() {
            BookMetadata english = result("Hyperion", "en", "9780000000001", "Publisher");
            BookMetadata russian = result("Гиперион", "ru", "9780000000002", "Publisher");

            for (String bookLanguage : List.of("ru", "rus", "ru-RU")) {
                List<BookMetadata> ranked = GoogleParser.rank(
                        List.of(english, russian),
                        context("Гиперион", false, bookLanguage));

                assertThat(ranked)
                        .as("bookLanguage=%s", bookLanguage)
                        .hasSize(2)
                        .containsExactly(russian, english);
            }
        }
    }

    @Nested
    class NoLanguageSignal {

        @Test
        void keepsTodaysOrderingWhenTheBookHasNoRecordedLanguage() {
            BookMetadata sparse = result("Hyperion", "en", null, null);
            BookMetadata complete = result("Hyperion", "en", "9780000000001", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(sparse, complete),
                    context("Hyperion Simmons", false, null));

            // No ISBN search and no book language: completeness alone decides, exactly as before.
            assertThat(ranked).hasSize(2).containsExactly(complete, sparse);
        }
    }

    @Nested
    class TieBreak {

        @Test
        void fallsBackToCompletenessWhenTheStrongerSignalsAreEqual() {
            BookMetadata sparseRussian = result("Гиперион", "ru", null, null);
            BookMetadata completeRussian = result("Гиперион", "ru", "9780000000002", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(sparseRussian, completeRussian),
                    context("Гиперион", false, "ru"));

            assertThat(ranked).hasSize(2).containsExactly(completeRussian, sparseRussian);
        }
    }

    @Nested
    class Degenerate {

        @Test
        void returnsAnEmptyListForNoResults() {
            assertThat(GoogleParser.rank(List.of(), context("Гиперион", false, "ru"))).isEmpty();
            assertThat(GoogleParser.rank(null, context("Гиперион", false, "ru"))).isEmpty();
        }
    }
}
