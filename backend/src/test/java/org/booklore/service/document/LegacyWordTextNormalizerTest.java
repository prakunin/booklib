package org.booklore.service.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyWordTextNormalizerTest {

    @Test
    void removesFieldsWithoutDisplayedResults() {
        var normalized = LegacyWordTextNormalizer.normalize(new String[]{
                "Maximov\u0013 XE \"Maximov, Andrey\"\u0015"
        });

        assertThat(normalized).containsExactly("Maximov");
    }

    @Test
    void preservesDisplayedResultsAndRemovesInstructions() {
        var normalized = LegacyWordTextNormalizer.normalize(new String[]{
                "Chapter\u0013 PAGEREF marker \\h \u0014 12\u0015"
        });

        assertThat(normalized).containsExactly("Chapter 12");
    }

    @Test
    void handlesNestedFieldsAcrossTextPieces() {
        var normalized = LegacyWordTextNormalizer.normalize(new String[]{
                "\u0013 TOC \\o \"1-1\" \\h \\z \u0014Contents",
                "Chapter\u0013 PAGEREF marker \\h \u0014 2\u0015",
                "\u0015Body"
        });

        assertThat(normalized).containsExactly("Contents", "Chapter 2", "Body");
    }

    @Test
    void restoresParagraphsEmbeddedInFallbackTextPieces() {
        var normalized = LegacyWordTextNormalizer.normalize(new String[]{
                "First\r\nSecond\rThird\nFourth"
        });

        assertThat(normalized).containsExactly("First", "Second", "Third", "Fourth");
    }

    @Test
    void leavesFieldTextUntouchedWhenAFieldIsUnbalanced() {
        String[] textPieces = {"Before\u0013 PAGEREF marker", "Body"};

        assertThat(LegacyWordTextNormalizer.normalize(textPieces)).containsExactly(textPieces);
    }

    @Test
    void leavesFieldTextUntouchedWhenASeparatorOrEndIsUnmatched() {
        String[] textPieces = {"Before\u0014Result", "After\u0015"};

        assertThat(LegacyWordTextNormalizer.normalize(textPieces)).containsExactly(textPieces);
    }
}
