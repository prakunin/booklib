package org.booklore.service.enrichment.catalog;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlibustaAnnotationParserTest {

    private final FlibustaAnnotationParser parser = new FlibustaAnnotationParser();

    private Map<String, String> parse(String xml) {
        return parser.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    class WellFormedDocuments {

        @Test
        void keysAnnotationsByEntryName() {
            Map<String, String> annotations = parse("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <folder name="f.fb2-173909-177717.zip">
                        <file name="110119.fb2"><p>Первый абзац.</p></file>
                        <file name="110125.fb2"><p>Другая книга.</p></file>
                    </folder>
                    """);

            assertThat(annotations)
                    .containsEntry("110119.fb2", "Первый абзац.")
                    .containsEntry("110125.fb2", "Другая книга.")
                    .hasSize(2);
        }

        @Test
        void joinsParagraphsWithBlankLine() {
            Map<String, String> annotations = parse("""
                    <folder name="a.zip">
                        <file name="1.fb2">
                            <p>Первый.</p>
                            <p>Второй.</p>
                        </file>
                    </folder>
                    """);

            assertThat(annotations).containsEntry("1.fb2", "Первый.\n\nВторой.");
        }

        @Test
        void stripsSurroundingWhitespaceInsideParagraphs() {
            Map<String, String> annotations = parse("""
                    <folder name="a.zip"><file name="1.fb2"><p>
                        Текст с отступами.
                    </p></file></folder>
                    """);

            assertThat(annotations).containsEntry("1.fb2", "Текст с отступами.");
        }

        @Test
        void skipsFilesWithoutText() {
            Map<String, String> annotations = parse("""
                    <folder name="a.zip">
                        <file name="empty.fb2"></file>
                        <file name="blank.fb2"><p>   </p></file>
                        <file name="real.fb2"><p>Есть текст.</p></file>
                    </folder>
                    """);

            assertThat(annotations).containsOnlyKeys("real.fb2");
        }
    }

    @Nested
    class BrokenInput {

        @Test
        void returnsEmptyForMalformedXmlRatherThanThrowing() {
            assertThat(parse("<folder name=\"a.zip\"><file name=\"1.fb2\"><p>оборвано")).isEmpty();
        }

        @Test
        void returnsEmptyForNullAndEmptyInput() {
            assertThat(parser.parse(null)).isEmpty();
            assertThat(parser.parse(new byte[0])).isEmpty();
        }

        /**
         * A catalog is data from an untrusted archive; a document declaring an external entity must
         * not make the parser open anything.
         */
        @Test
        void doesNotResolveExternalEntities() {
            Map<String, String> annotations = parse("""
                    <?xml version="1.0"?>
                    <!DOCTYPE folder [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <folder name="a.zip"><file name="1.fb2"><p>&xxe;</p></file></folder>
                    """);

            assertThat(annotations).isEmpty();
        }
    }
}
