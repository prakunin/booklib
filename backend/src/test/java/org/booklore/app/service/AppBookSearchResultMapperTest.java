package org.booklore.app.service;

import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.DocumentParseStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppBookSearchResultMapperTest {

    @Test
    void exposesPrimaryDocumentParseStatus() {
        BookFileEntity file = BookFileEntity.builder()
                .fileName("report.docx")
                .bookType(BookFileType.DOC)
                .isBookFormat(true)
                .documentParseStatus(DocumentParseStatus.UNREADABLE)
                .build();
        BookEntity book = BookEntity.builder()
                .id(9L)
                .metadata(BookMetadataEntity.builder().title("Report").build())
                .bookFiles(List.of(file))
                .build();

        var result = AppBookSearchResultMapper.toResult(book);

        assertThat(result.primaryFileDocumentParseStatus()).isEqualTo("UNREADABLE");
    }

    @Test
    void mapsCompleteMetadataAndPrimaryFile() {
        Instant coverUpdatedOn = Instant.parse("2026-01-02T03:04:05Z");
        Instant audiobookCoverUpdatedOn = Instant.parse("2026-02-03T04:05:06Z");
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .title("Title")
                .authors(List.of(
                        AuthorEntity.builder().name("First").build(),
                        AuthorEntity.builder().name("Second").build()))
                .seriesName("Series")
                .seriesNumber(2.5F)
                .publishedDate(LocalDate.of(2025, Month.JUNE, 7))
                .coverUpdatedOn(coverUpdatedOn)
                .audiobookCoverUpdatedOn(audiobookCoverUpdatedOn)
                .build();
        BookFileEntity file = BookFileEntity.builder()
                .fileName("book.epub")
                .bookType(BookFileType.EPUB)
                .isBookFormat(true)
                .build();
        BookEntity book = BookEntity.builder()
                .id(10L)
                .metadata(metadata)
                .bookFiles(List.of(file))
                .build();

        var result = AppBookSearchResultMapper.toResult(book);

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.title()).isEqualTo("Title");
        assertThat(result.authors()).containsExactly("First", "Second");
        assertThat(result.seriesName()).isEqualTo("Series");
        assertThat(result.seriesNumber()).isEqualTo(2.5F);
        assertThat(result.publishedDate()).isEqualTo(LocalDate.of(2025, Month.JUNE, 7));
        assertThat(result.primaryFileType()).isEqualTo("EPUB");
        assertThat(result.primaryFileDocumentParseStatus()).isNull();
        assertThat(result.primaryFileName()).isEqualTo("book.epub");
        assertThat(result.coverUpdatedOn()).isEqualTo(coverUpdatedOn);
        assertThat(result.audiobookCoverUpdatedOn()).isEqualTo(audiobookCoverUpdatedOn);
    }

    @Test
    void mapsMissingMetadataAndPrimaryFileToEmptyValues() {
        var result = AppBookSearchResultMapper.toResult(BookEntity.builder().id(11L).build());

        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.title()).isNull();
        assertThat(result.authors()).isEmpty();
        assertThat(result.seriesName()).isNull();
        assertThat(result.seriesNumber()).isNull();
        assertThat(result.publishedDate()).isNull();
        assertThat(result.primaryFileType()).isNull();
        assertThat(result.primaryFileDocumentParseStatus()).isNull();
        assertThat(result.primaryFileName()).isNull();
        assertThat(result.coverUpdatedOn()).isNull();
        assertThat(result.audiobookCoverUpdatedOn()).isNull();
    }

    @Test
    void mapsNullAuthorCollectionToEmptyList() {
        BookMetadataEntity metadata = BookMetadataEntity.builder().title("Title").build();
        metadata.setAuthors(null);

        var result = AppBookSearchResultMapper.toResult(BookEntity.builder().metadata(metadata).build());

        assertThat(result.authors()).isEmpty();
    }
}
