package org.booklore.app.dto;

import java.util.List;

/**
 * Compact, server-aggregated input for the library statistics screen.
 *
 * <p>The old screen downloaded every visible book and aggregated it in the browser.  Keeping the
 * facets and time series in this response makes its size depend on the number of distinct chart
 * values, not on the number of books in the catalog.</p>
 */
public record AppLibraryStats(
        long totalBooks,
        long totalSizeKb,
        long totalAuthors,
        long totalSeries,
        long totalPublishers,
        long averageDaysToFinish,
        AppFilterOptions facets,
        List<MonthlyCount> booksAddedByMonth,
        List<MonthlyCount> booksFinishedByMonth,
        List<AuthorStat> authorStats,
        List<BookFlowCount> bookFlow,
        List<PublicationRatingCount> publicationRatings,
        List<PageRatingCount> pageRatings,
        List<RatingTasteCount> ratingTaste) {

    public record MonthlyCount(int year, int month, long count) {}

    public record AuthorStat(
            String name,
            long bookCount,
            long totalPages,
            double averageRating,
            long readCount) {}

    public record BookFlowCount(
            int year,
            int quarter,
            String readStatus,
            Integer personalRating,
            long count) {}

    public record PublicationRatingCount(int year, int personalRating, long count) {}

    public record PageRatingCount(int pageCount, int personalRating, String readStatus, long count) {}

    public record RatingTasteCount(
            int personalRating,
            Double metadataRating,
            Double goodreadsRating,
            Double amazonRating,
            Double hardcoverRating,
            Double lubimyczytacRating,
            Double ranobedbRating,
            long count) {}
}
