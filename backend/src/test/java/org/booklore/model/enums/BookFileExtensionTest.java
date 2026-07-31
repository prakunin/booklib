package org.booklore.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookFileExtensionTest {

    @Test
    void djvuFileNamesResolveToTheDjvuType() {
        assertThat(BookFileExtension.fromFileName("scan.djvu"))
                .contains(BookFileExtension.DJVU);
        assertThat(BookFileExtension.fromFileName("scan.djv"))
                .contains(BookFileExtension.DJV);
        assertThat(BookFileExtension.fromFileName("Scan.DJVU").map(BookFileExtension::getType))
                .contains(BookFileType.DJVU);
    }

    @Test
    void unknownExtensionsStayUnrecognised() {
        assertThat(BookFileExtension.fromFileName("scan.rtf")).isEmpty();
    }
}
