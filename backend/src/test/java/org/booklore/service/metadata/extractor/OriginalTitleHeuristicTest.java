package org.booklore.service.metadata.extractor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class OriginalTitleHeuristicTest {

    @Nested
    class Accepts {

        @ParameterizedTest
        @ValueSource(strings = {
                "Laurell K. Hamilton. «Affliction», 2013",
                "The eighth book in the Bullet Catchers series, 2009",
                "Ullstein, 1995",
                "\"Homo homini lupus est\""
        })
        void titlePageLinesThatNameAWorkOrItsEdition(String value) {
            assertThat(OriginalTitleHeuristic.looksLikeOriginalTitle(value)).isTrue();
        }
    }

    @Nested
    class Rejects {

        @ParameterizedTest
        @ValueSource(strings = {
                "ISBN 5-7281-0149-6",
                "ISBN 978-5-91250-619-2",
                "ISSN 2409-0069",
                "NZ-Titul-RD-1.qxp 2/7/2005 11:15 AMч Page 1",
                "Чтобы изменить документ по умолчанию, отредактируйте файл \"blank.fb2\" вручную.",
                "© Электронная версия книги подготовлена компанией ЛитРес (www.litres.ru), 2014",
                "Copyright © 2012 by Morgan Rice",
                "ozzy1957@yandex.ru",
                "http://lady.webnice.ru/forum/viewtopic.php?t=5151",
                "section class=\"box-today\"",
                "2 u=\"u605.54.spylog.com\";d=document;nv=navigator;na=nv.appName;p=0;j=\"N\";"
        })
        void boilerplateAndMarkupThatOnlyLooksLikeATitle(String value) {
            assertThat(OriginalTitleHeuristic.looksLikeOriginalTitle(value)).isFalse();
        }

        @Test
        void prosePastTheLengthATitleCanPlausiblyReach() {
            String paragraph = "Нас так воодушевил успех сборника «Many Bloody Returns», что мы тут же "
                    + "бросились составлять следующий, и для второго сборника мы тоже решили выбрать "
                    + "две темы, 2010";
            assertThat(OriginalTitleHeuristic.looksLikeOriginalTitle(paragraph)).isFalse();
        }

        @Test
        void linesCarryingNoLatinTitleAtAll() {
            assertThat(OriginalTitleHeuristic.looksLikeOriginalTitle("Москва 1999")).isFalse();
        }

        @Test
        void blankValues() {
            assertThat(OriginalTitleHeuristic.looksLikeOriginalTitle(null)).isFalse();
            assertThat(OriginalTitleHeuristic.looksLikeOriginalTitle("  ")).isFalse();
        }
    }
}
