package org.booklore.service.enrichment.catalog;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlibustaContentsParserTest {

    private final FlibustaContentsParser parser = new FlibustaContentsParser();

    private final List<FlibustaContentsParser.CatalogRow> collected = new ArrayList<>();

    private int parse(String tsv) {
        return parser.parse(new ByteArrayInputStream(tsv.getBytes(StandardCharsets.UTF_8)),
                collected::add);
    }

    @Test
    void readsArchiveAndEntryFromTheLastTwoColumns() {
        int count = parse("Жун,Цзян,:\tWolf Totem (chinese)\t\tfb2-091841-104214.zip\t95887.fb2\n"
                + "Толстой,Лев,:\tВойна и мир\t\tf.fb2-173909-177717.zip\t174393.fb2\n");

        assertThat(count).isEqualTo(2);
        assertThat(collected).containsExactly(
                new FlibustaContentsParser.CatalogRow(
                        "Wolf Totem (chinese)", List.of("Жун Цзян"),
                        "fb2-091841-104214.zip", "95887.fb2"),
                new FlibustaContentsParser.CatalogRow(
                        "Война и мир", List.of("Толстой Лев"),
                        "f.fb2-173909-177717.zip", "174393.fb2"));
    }

    @Test
    void skipsRowsWithoutEnoughColumns() {
        int count = parse("only\ttwo\n\nauthor\ttitle\t\tarchive.zip\n");

        assertThat(count).isZero();
        assertThat(collected).isEmpty();
    }

    @Test
    void skipsRowsWhoseArchiveOrEntryIsBlank() {
        int count = parse("a\tb\t\t\t95887.fb2\n"
                + "a\tb\t\tarchive.zip\t\n");

        assertThat(count).isZero();
        assertThat(collected).isEmpty();
    }

    /**
     * ru.txt is around 90 MB. The parser must stream: it is handed a reader and never materialises
     * the document, so a large input costs memory proportional to one line.
     */
    @Test
    void streamsLargeInputWithoutHoldingIt() {
        StringBuilder tsv = new StringBuilder();
        for (int i = 0; i < 50_000; i++) {
            tsv.append("author\ttitle\t\tarchive.zip\t").append(i).append(".fb2\n");
        }

        int count = parse(tsv.toString());

        assertThat(count).isEqualTo(50_000);
        assertThat(collected).hasSize(50_000);
        assertThat(collected.getLast().archiveName()).isEqualTo("archive.zip");
        assertThat(collected.getLast().entryName()).isEqualTo("49999.fb2");
    }

    @Test
    void splitsSeveralAuthorsAndDropsEmptyTrailingComponents() {
        int count = parse("Толстой,Лев,Николаевич:Ильф,Илья,:	Книга		books.zip	1.fb2\n");

        assertThat(count).isEqualTo(1);
        assertThat(collected.getFirst().authors())
                .containsExactly("Толстой Лев Николаевич", "Ильф Илья");
    }

    @Test
    void keepsAUsableRowWhenIdentityFieldsAreBlank() {
        int count = parse("\t\t\tbooks.zip\t1.fb2\n");

        assertThat(count).isEqualTo(1);
        assertThat(collected.getFirst().title()).isEmpty();
        assertThat(collected.getFirst().authors()).isEmpty();
    }
}
