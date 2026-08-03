package org.booklore.service.enrichment.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlibustaAuthorKeyTest {

    /**
     * Taken from the live catalog: {@code authors/10.7z} files Daniel Handler's biography under this
     * hash. If the normalization ever drifts, every author lookup silently returns nothing, so the
     * real value is pinned here rather than recomputed by the test.
     */
    private static final String HANDLER = "006018ce8ceae84a232fa485f005c325";

    @Test
    void hashesTheNameAsTheCatalogFilesIt() {
        assertThat(FlibustaAuthorKey.of("Хэндлер Дэниел")).isEqualTo(HANDLER);
    }

    @Test
    void isCaseInsensitive() {
        assertThat(FlibustaAuthorKey.of("ХЭНДЛЕР ДЭНИЕЛ")).isEqualTo(HANDLER);
    }

    @Test
    void collapsesSurroundingAndRepeatedWhitespace() {
        assertThat(FlibustaAuthorKey.of("  Хэндлер   Дэниел  ")).isEqualTo(HANDLER);
    }

    @Test
    void returnsNullForMissingName() {
        assertThat(FlibustaAuthorKey.of(null)).isNull();
        assertThat(FlibustaAuthorKey.of("   ")).isNull();
    }

    /**
     * The catalog files surname first, which is the order the INPX importer already produces from
     * the raw {@code "Хэндлер,Дэниел,"} field. Given-name-first must therefore not collide.
     */
    @Test
    void isSensitiveToNameOrder() {
        assertThat(FlibustaAuthorKey.of("Дэниел Хэндлер")).isNotEqualTo(HANDLER);
    }
}
