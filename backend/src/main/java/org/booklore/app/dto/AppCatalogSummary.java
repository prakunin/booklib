package org.booklore.app.dto;

import java.util.Map;

public record AppCatalogSummary(
        long totalBooks,
        long totalAuthors,
        long totalSeries,
        long unshelvedBooks,
        Map<Long, Long> libraryBookCounts) {
}
