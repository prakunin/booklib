package org.booklore.service.metadata;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.MetadataField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataFieldAccessorsTest {

    /**
     * The guard that keeps the proposal path from quietly attributing only some of a book's fields:
     * a {@code MetadataField} added without an accessor would be silently skipped rather than fail.
     */
    @ParameterizedTest
    @EnumSource(MetadataField.class)
    void readsEveryAttributableField(MetadataField field) {
        assertThat(MetadataFieldAccessors.covers(field))
                .as("%s has no accessor, so its provenance would be silently dropped on the proposal path", field)
                .isTrue();
    }

    @Test
    void readsTheValueBehindAField() {
        BookMetadata metadata = BookMetadata.builder()
                .title("A Title")
                .pageCount(321)
                .publishedDate(LocalDate.of(2026, 8, 4))
                .goodreadsRating(4.5)
                .build();

        assertThat(MetadataFieldAccessors.valueOf(MetadataField.TITLE, metadata)).isEqualTo("A Title");
        assertThat(MetadataFieldAccessors.valueOf(MetadataField.PAGE_COUNT, metadata)).isEqualTo(321);
        assertThat(MetadataFieldAccessors.valueOf(MetadataField.PUBLISHED_DATE, metadata))
                .isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(MetadataFieldAccessors.valueOf(MetadataField.GOODREADS_RATING, metadata)).isEqualTo(4.5);
        assertThat(MetadataFieldAccessors.valueOf(MetadataField.SUBTITLE, metadata)).isNull();
    }

    @Test
    void toleratesBeingAskedAboutNothing() {
        assertThat(MetadataFieldAccessors.valueOf(MetadataField.TITLE, null)).isNull();
        assertThat(MetadataFieldAccessors.valueOf(null, BookMetadata.builder().build())).isNull();
    }
}
