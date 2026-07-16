package org.booklore.app.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppBookServiceCatalogSummaryTest {

    @Test
    void catalogSummaryKeySupportsAdminAccessToAllLibraries() {
        String key = AppBookService.catalogSummaryKey(7L, true, null);

        assertThat(key).isEqualTo("7|true|*");
    }

    @Test
    void catalogSummaryKeySortsAccessibleLibraryIds() {
        String key = AppBookService.catalogSummaryKey(7L, false, Set.of(3L, 1L, 2L));

        assertThat(key).isEqualTo("7|false|1,2,3");
    }
}
