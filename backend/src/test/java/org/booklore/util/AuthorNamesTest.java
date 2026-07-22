package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AuthorNamesTest {

    private static Stream<Arguments> cleaned() {
        return Stream.of(
            arguments("  James   M.  Ward ", "James M. Ward"),   // trim + collapse
            arguments("Джеймс", "Джеймс"),                        // Unicode preserved
            arguments("Café", "Café"),                            // NFC compose
            arguments("   ", ""),                                 // blank -> empty
            arguments(null, ""),                                  // null -> empty
            arguments("James Ward", "James Ward"),            // NBSP collapses to plain space
            arguments("A B", "A B")                           // NNBSP collapses to plain space
        );
    }

    @ParameterizedTest
    @MethodSource("cleaned")
    void cleansDisplayName(String raw, String expected) {
        assertThat(AuthorNames.cleanDisplayName(raw)).isEqualTo(expected);
    }

    private static Stream<Arguments> keys() {
        return Stream.of(
            arguments("J. K. Rowling", "j k rowling"),
            arguments("J.K. Rowling", "j k rowling"),
            arguments("Café", "cafe"),                              // diacritics folded
            arguments("Ursula K. Le Guin", "ursula k le guin")
        );
    }

    @ParameterizedTest
    @MethodSource("keys")
    void normalizesKey(String cleaned, String expected) {
        assertThat(AuthorNames.normalizeKey(cleaned)).isEqualTo(expected);
    }

    @Test
    void normalizeKeyCollapsesInitialsPunctuationConsistently() {
        assertThat(AuthorNames.normalizeKey("J.K. Rowling"))
                .isEqualTo(AuthorNames.normalizeKey("J. K. Rowling"));
    }

    @Test
    void normalizeKeyCollapsesUnicodeDashAndNbspToSingleSpace() {
        // em-dash (U+2014) and NBSP (U+00A0) are Unicode punctuation and separators, unlike plain ASCII punctuation
        assertThat(AuthorNames.normalizeKey("Anne—Marie Smith"))
                .isEqualTo("anne marie smith");
    }

    @Test
    void clampByCodePointsDoesNotSplitSurrogatePairs() {
        String emoji = "😀😀😀"; // 3 code points, 6 chars
        String clamped = AuthorNames.clampByCodePoints(emoji, 2);
        assertThat(clamped.codePointCount(0, clamped.length())).isEqualTo(2);
        assertThat(Character.isHighSurrogate(clamped.charAt(clamped.length() - 1))).isFalse();
    }
}
