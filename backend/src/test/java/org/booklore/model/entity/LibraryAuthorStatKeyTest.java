package org.booklore.model.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryAuthorStatKeyTest {

    @Test
    void equalityUsesBothIdentifiers() {
        LibraryAuthorStatKey key = new LibraryAuthorStatKey(1L, 2L);
        LibraryAuthorStatKey equal = new LibraryAuthorStatKey(1L, 2L);

        assertThat(key)
                .isEqualTo(key)
                .isEqualTo(equal)
                .hasSameHashCodeAs(equal)
                .isNotEqualTo(new LibraryAuthorStatKey(9L, 2L))
                .isNotEqualTo(new LibraryAuthorStatKey(1L, 9L))
                .isNotEqualTo(null)
                .isNotEqualTo("not a key");
    }

    @Test
    void defaultKeysWithNullIdentifiersAreEqual() {
        assertThat(new LibraryAuthorStatKey())
                .isEqualTo(new LibraryAuthorStatKey())
                .hasSameHashCodeAs(new LibraryAuthorStatKey());
    }
}
