package org.booklore.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookFileExtensionTest {

    @Test
    void fromFileName_recognizesZippedFb2Wrapper() {
        assertThat(BookFileExtension.fromFileName("book.fb2.zip")).contains(BookFileExtension.FB2);
        assertThat(BookFileExtension.fromFileName("BOOK.FB2.ZIP")).contains(BookFileExtension.FB2);
    }

    @Test
    void fromFileName_doesNotTreatGenericZipAsBookExtension() {
        assertThat(BookFileExtension.fromFileName("archive.zip")).isEmpty();
    }
}
