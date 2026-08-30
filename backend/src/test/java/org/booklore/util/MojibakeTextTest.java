package org.booklore.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MojibakeTextTest {

    @Nested
    class Detects {

        @Test
        void aTitleThatIsNothingButReplacementCharacters() {
            assertThat(MojibakeText.isMojibake("�".repeat(12) + " " + "�".repeat(10)))
                    .isTrue();
        }

        @Test
        void aValueWhereReplacementCharactersDominate() {
            assertThat(MojibakeText.isMojibake("�����a")).isTrue();
        }
    }

    @Nested
    class LeavesAlone {

        @Test
        void ordinaryText() {
            assertThat(MojibakeText.isMojibake("Библия. Ветхий Завет. РБО 2011")).isFalse();
        }

        @Test
        void blankAndNullValues() {
            assertThat(MojibakeText.isMojibake(null)).isFalse();
            assertThat(MojibakeText.isMojibake("   ")).isFalse();
        }

        @Test
        void aLongTitleCarryingOneStrayUndecodableCharacter() {
            assertThat(MojibakeText.isMojibake("Caf� au lait and other stories")).isFalse();
        }

        @Test
        void aShortTitleWithTwoStrayCharacters() {
            assertThat(MojibakeText.isMojibake("Cr�me br�lée")).isFalse();
        }
    }

    @Test
    void scrubReturnsNullOnlyForMojibake() {
        assertThat(MojibakeText.scrub("����")).isNull();
        assertThat(MojibakeText.scrub("Война и мир")).isEqualTo("Война и мир");
        assertThat(MojibakeText.scrub(null)).isNull();
    }
}
