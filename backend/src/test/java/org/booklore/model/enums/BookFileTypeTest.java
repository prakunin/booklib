package org.booklore.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookFileTypeTest {

    @Test
    void otherIsAnInertCatchAllNeverReturnedByExtension() {
        // OTHER is the download-only catch-all the INPX path assigns explicitly. It must carry no
        // extensions, so global extension-based classification never resolves a file to it.
        assertThat(BookFileType.fromName("OTHER")).contains(BookFileType.OTHER);
        assertThat(BookFileType.OTHER.getExtensions()).isEmpty();
        assertThat(BookFileType.OTHER.supports("rtf")).isFalse();
        assertThat(BookFileType.fromExtension("rtf")).isEmpty();
        assertThat(BookFileType.fromExtension("doc")).isEmpty();
    }

    @Test
    void djvuResolvesFromBothExtensions() {
        assertThat(BookFileType.fromExtension("djvu")).contains(BookFileType.DJVU);
        assertThat(BookFileType.fromExtension("djv")).contains(BookFileType.DJVU);
        assertThat(BookFileType.fromExtension("DJVU")).contains(BookFileType.DJVU);
        assertThat(BookFileType.fromName("DJVU")).contains(BookFileType.DJVU);
    }
}
