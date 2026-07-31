package org.booklore.service.metadata.smart;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkIdentityPromptBuilderTest {

    private final WorkIdentityPromptBuilder builder = new WorkIdentityPromptBuilder();

    private String deep(Book book) {
        return builder.build(book, true, null);
    }

    private String quick(Book book) {
        return builder.build(book, false, null);
    }

    @Test
    void deepModeStatesTheRulesTheRestOfThePipelineRelieson() {
        String prompt = deep(Book.builder().title("A").build());

        assertThat(prompt)
                .contains("identify the literary WORK")
                .contains("Using web search")
                .contains("Never invent a value")
                .contains("copied verbatim")
                .contains("\"goodreads_url\"");
    }

    // The default cheap pass must forbid both web access and — because it cannot quote a page — any
    // description at all, so the model never fabricates one.
    @Test
    void quickModeForbidsWebAccessAndDescriptions() {
        String prompt = quick(Book.builder().title("A").build());

        assertThat(prompt)
                .contains("FROM YOUR OWN KNOWLEDGE ONLY")
                .contains("Do NOT search the web")
                .contains("Do NOT write a description")
                .doesNotContain("copied verbatim")
                .doesNotContain("Using web search");
    }

    @Test
    void marksAbsentFieldsRatherThanOmittingThem() {
        String prompt = quick(Book.builder()
                .title("Путевой дневник")
                .metadata(BookMetadata.builder().title("Путевой дневник").build())
                .build());

        assertThat(prompt)
                .contains("title: Путевой дневник")
                .contains("authors: (missing)")
                .contains("isbn: (missing)");
    }

    @Test
    void fallsBackToTheBookTitleWhenThereIsNoMetadata() {
        assertThat(quick(Book.builder().title("Bare title").build())).contains("title: Bare title");
    }

    @Test
    void prefersIsbn13OverIsbn10() {
        String prompt = quick(Book.builder()
                .metadata(BookMetadata.builder().isbn13("9781234567897").isbn10("1234567897").build())
                .build());

        assertThat(prompt).contains("isbn: 9781234567897").doesNotContain("isbn: 1234567897");
    }

    @Test
    void joinsMultipleAuthors() {
        String prompt = quick(Book.builder()
                .metadata(BookMetadata.builder().authors(List.of("Ильф", "Петров")).build())
                .build());

        assertThat(prompt).contains("authors: Ильф, Петров");
    }

    // The filename is often the only usable signal when the FB2/EPUB internals are empty.
    @Test
    void includesTheFilenameAsASearchHint() {
        String prompt = quick(Book.builder()
                .primaryFile(BookFile.builder().fileName("Хантер. Красные шатры 1. Рассвет рыцаря.fb2").build())
                .build());

        assertThat(prompt)
                .contains("filename: Хантер. Красные шатры 1. Рассвет рыцаря")
                .doesNotContain(".fb2");
    }

    @Test
    void turnsUnderscoreSeparatorsIntoSpacesAndDropsTheExtension() {
        String prompt = quick(Book.builder()
                .primaryFile(BookFile.builder().fileName("Asimov_Foundation_1951.epub").build())
                .build());

        assertThat(prompt).contains("filename: Asimov Foundation 1951");
    }

    @Test
    void marksTheFilenameMissingWhenThereIsNoFile() {
        assertThat(quick(Book.builder().title("A").build())).contains("filename: (missing)");
    }

    // The opening pages let the no-web pass identify the book, so they must reach the prompt inside a
    // delimited block that keeps their line breaks from bleeding into the INPUT fields.
    @Test
    void includesTheBookExcerptWhenProvided() {
        String prompt = builder.build(Book.builder().title("A").build(), false,
                "Сергей Хантер\nКрасные шатры\nРассвет рыцаря\nИздательство АСТ, 2007");

        assertThat(prompt)
                .contains("book_excerpt (opening pages of the file):")
                .contains("Рассвет рыцаря")
                .contains("Издательство АСТ");
    }

    @Test
    void removesConverterWatermarksButKeepsIdentifyingProse() {
        String prompt = builder.build(Book.builder().title("A").build(), false, """
                F-XChange View
                F-XChange View
                D
                e
                Click to buy NOW!
                c u-track.co
                3
                Тысячи лет люди не знали, что живут в своих воспоминаниях.
                Перед началом
                До момента написания этой книги, я не видел никого, кто достиг бы просветления.
                """);

        assertThat(prompt)
                .contains("Тысячи лет люди не знали")
                .contains("Перед началом")
                .contains("До момента написания этой книги")
                .doesNotContain("F-XChange", "Click to buy NOW", "u-track.co");
    }

    @Test
    void omitsTheExcerptWhenOnlyConversionNoiseRemains() {
        String prompt = builder.build(Book.builder().title("A").build(), false, """
                F-XChange View
                Click to buy NOW!
                D
                e
                3
                """);

        assertThat(prompt).doesNotContain("book_excerpt (opening pages of the file):");
    }

    @Test
    void omitsTheExcerptBlockWhenThereIsNone() {
        // The rules mention book_excerpt by name, so absence is asserted on the delimited block header.
        assertThat(quick(Book.builder().title("A").build()))
                .doesNotContain("book_excerpt (opening pages of the file):");
    }

    // A stored blurb helps identify the work, but pasting a full one would crowd out the rules.
    @Test
    void truncatesAnExistingDescription() {
        String longDescription = "Слово ".repeat(200);
        String prompt = deep(Book.builder()
                .metadata(BookMetadata.builder().description(longDescription).build())
                .build());

        assertThat(prompt).contains("existing_description: Слово").contains("…");
        // The truncated blurb (~300 chars) must not blow the prompt up; the ceiling leaves room for
        // the fixed rules/schema text and still catches a full 200-word description leaking through.
        assertThat(prompt.length()).isLessThan(4500);
    }
}
