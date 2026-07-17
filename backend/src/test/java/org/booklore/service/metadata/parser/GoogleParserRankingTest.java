package org.booklore.service.metadata.parser;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ranking's job is to order results by how well each answers the query that produced it.
 * Nothing here may drop a result: langRestrict already filters, and a filter can make a mis-tagged
 * edition vanish outright, which is why the language signal is a boost and lives here.
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

    @Nested
    class IsbnSearches {

        @Test
        void putsTheExactIsbnMatchFirstEvenWhenItHasFewerFields() {
            BookMetadata sparseButExact = result("Right book", null, "9785171147426", null);
            BookMetadata richButWrong = result("Wrong book", "en", "9780000000000", "Some Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(richButWrong, sparseButExact), "9785171147426", true);

            assertThat(ranked).first().isSameAs(sparseButExact);
        }

        @Test
        void ignoresHyphensAndSpacesWhenMatchingTheIsbn() {
            BookMetadata exact = result("Right book", null, "9785171147426", null);
            BookMetadata other = result("Other book", "en", "9780000000000", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(other, exact), "978-5-17-114742-6", true);

            assertThat(ranked).first().isSameAs(exact);
        }

        @Test
        void doesNotApplyTheIsbnSignalWhenTheSearchWasNotAnIsbnSearch() {
            BookMetadata sparseWithThatIsbn = result("Sparse", null, "9785171147426", null);
            BookMetadata complete = result("Complete", null, "9780000000000", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(sparseWithThatIsbn, complete), "9785171147426", false);

            assertThat(ranked).first().isSameAs(complete);
        }
    }

    @Nested
    class CyrillicQueries {

        @Test
        void ranksTheRussianResultAboveAnEnglishOneOfEqualCompleteness() {
            BookMetadata english = result("Hyperion", "en", "9780000000001", "Publisher");
            BookMetadata russian = result("Гиперион", "ru", "9780000000002", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(english, russian), "Гиперион Симмонс", false);

            assertThat(ranked).first().isSameAs(russian);
        }

        @Test
        void treatsRuAndRusAndRuRuAsTheSameLanguage() {
            BookMetadata english = result("Hyperion", "en", "9780000000001", "Publisher");
            BookMetadata russian = result("Гиперион", "rus", "9780000000002", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(english, russian), "Гиперион", false);

            assertThat(ranked).first().isSameAs(russian);
        }
    }

    @Nested
    class LatinQueries {

        @Test
        void keepsTodaysOrderingWhenTheQueryCarriesNoLanguageSignal() {
            BookMetadata sparse = result("Hyperion", "en", null, null);
            BookMetadata complete = result("Hyperion", "en", "9780000000001", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(sparse, complete), "Hyperion Simmons", false);

            // No ISBN search and no Cyrillic: completeness alone decides, exactly as before.
            assertThat(ranked).first().isSameAs(complete);
        }
    }

    @Nested
    class TieBreak {

        @Test
        void fallsBackToCompletenessWhenTheStrongerSignalsAreEqual() {
            BookMetadata sparseRussian = result("Гиперион", "ru", null, null);
            BookMetadata completeRussian = result("Гиперион", "ru", "9780000000002", "Publisher");

            List<BookMetadata> ranked = GoogleParser.rank(
                    List.of(sparseRussian, completeRussian), "Гиперион", false);

            assertThat(ranked).first().isSameAs(completeRussian);
        }
    }

    @Nested
    class Degenerate {

        @Test
        void returnsAnEmptyListForNoResults() {
            assertThat(GoogleParser.rank(List.of(), "Гиперион", false)).isEmpty();
            assertThat(GoogleParser.rank(null, "Гиперион", false)).isEmpty();
        }
    }
}
