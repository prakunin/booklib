package org.booklore.service.inpx;

import org.booklore.model.dto.inpx.InpxBookReference;
import org.booklore.model.dto.inpx.InpxSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

import org.booklore.model.dto.inpx.InpxBookDto;

class InpxParserTest {

    private static final char SEPARATOR = 0x04;

    @TempDir
    Path tempDir;

    private final InpxParser parser = new InpxParser();

    @Test
    void searchesFb2EntriesAndMapsTheirArchive() throws IOException {
        Path index = createIndex(
                row("Иванов,Иван:", "fantasy", "Нужная книга", "Цикл", "2", "123", "42", "123", "", "fb2", "2020", "ru", "", "magic") + "\n"
                        + row("Петров,Петр:", "detective", "Другая книга", "", "", "124", "43", "124", "", "fb2", "2021", "ru", "", "") + "\n"
                        + row("Удален,Автор:", "fantasy", "Удаленная", "", "", "125", "44", "125", "1", "fb2", "2022", "ru", "", "") + "\n"
                        + row("Комикс,Автор:", "comic", "Не FB2", "", "", "126", "45", "126", "", "pdf", "2023", "ru", "", ""));

        InpxSearchResult result = parser.search(index.toString(), "иванов", 10);

        assertThat(result.getScannedCount()).isEqualTo(2);
        assertThat(result.isTruncated()).isFalse();
        assertThat(result.getBooks()).singleElement().satisfies(book -> {
            assertThat(book.getId()).isEqualTo("fb2-000001-000999.zip|123.fb2");
            assertThat(book.getAuthors()).containsExactly("Иванов Иван");
            assertThat(book.getTitle()).isEqualTo("Нужная книга");
            assertThat(book.getSeries()).isEqualTo("Цикл");
            assertThat(book.getArchiveName()).isEqualTo("fb2-000001-000999.zip");
        });
    }

    @Test
    void limitsResultsAndResolvesOnlyRequestedBooks() throws IOException {
        Path index = createIndex(
                row("Автор,Один:", "genre", "Первая", "", "", "123", "42", "123", "", "fb2", "", "ru", "", "") + "\n"
                        + row("Автор,Два:", "genre", "Вторая", "", "", "124", "43", "124", "", "fb2", "", "ru", "", ""));

        InpxSearchResult result = parser.search(index.toString(), "", 1);
        InpxBookReference reference = new InpxBookReference();
        reference.setArchiveName("fb2-000001-000999.zip");
        reference.setFileName("124");
        reference.setExtension("fb2");

        Map<String, ?> resolved = parser.resolve(index.toString(), List.of(reference));

        assertThat(result.getBooks()).hasSize(1);
        assertThat(result.isTruncated()).isTrue();
        assertThat(resolved).containsOnlyKeys("fb2-000001-000999.zip|124.fb2");
    }

    @Test
    void parsesLibrateAsRating() throws IOException {
        // AUTHOR;GENRE;TITLE;SERIES;SERNO;FILE;SIZE;LIBID;DEL;EXT;DATE;LANG;LIBRATE;KEYWORDS
        Path index = createIndex(
                row("Strugatsky,Arkady:", "sf", "Roadside Picnic", "", "", "12345", "100", "1", "0", "fb2",
                        "2024-01-01", "ru", "5", ""));

        List<InpxBookDto> books = new ArrayList<>();
        parser.forEach(index.toString(), books::add);

        assertThat(books).singleElement()
                .extracting(InpxBookDto::getRating)
                .isEqualTo(5.0);
    }

    @Test
    void leavesRatingNullWhenLibrateIsBlankOrJunk() throws IOException {
        Path index = createIndex(
                row("A:", "sf", "No rating", "", "", "1", "10", "1", "0", "fb2", "", "ru", "", "") + "\n"
                        + row("B:", "sf", "Junk rating", "", "", "2", "10", "2", "0", "fb2", "", "ru", "n/a", ""));

        List<InpxBookDto> books = new ArrayList<>();
        parser.forEach(index.toString(), books::add);

        assertThat(books).extracting(InpxBookDto::getRating).containsExactly(null, null);
    }

    @Test
    void countMatchesTheNumberOfEmittedRecords() throws IOException {
        Path index = createIndex(
                row("A:", "sf", "Kept", "", "", "1", "10", "1", "0", "fb2", "", "ru", "", "") + "\n"
                        + row("B:", "sf", "Deleted", "", "", "2", "10", "2", "1", "fb2", "", "ru", "", "") + "\n"
                        + row("C:", "sf", "Not fb2", "", "", "3", "10", "3", "0", "epub", "", "ru", "", ""));

        assertThat(parser.count(index.toString())).isEqualTo(1L);

        List<InpxBookDto> books = new ArrayList<>();
        parser.forEach(index.toString(), books::add);
        assertThat(books).hasSize(1);
    }

    private Path createIndex(String inpContents) throws IOException {
        Path index = tempDir.resolve("library.inpx");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(index), StandardCharsets.UTF_8)) {
            output.putNextEntry(new ZipEntry("structure.info"));
            output.write("AUTHOR;GENRE;TITLE;SERIES;SERNO;FILE;SIZE;LIBID;DEL;EXT;DATE;LANG;LIBRATE;KEYWORDS"
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new ZipEntry("fb2-000001-000999.inp"));
            output.write(inpContents.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return index;
    }

    private String row(String... values) {
        return String.join(String.valueOf(SEPARATOR), values);
    }
}
