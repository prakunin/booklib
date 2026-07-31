package org.booklore.model.dto.smart;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * What the agent claims about the literary work behind a file, before any of it is verified.
 * <p>
 * Everything here is a lead, not a fact. {@link #reportedRating()} in particular is the agent's own
 * reading of a rating page and is never written to metadata — it exists so the verified number
 * fetched by the Goodreads parser can be compared against it.
 * <p>
 * The work fields describe the original composition; the edition fields describe the release the
 * file came from. They are kept apart because they answer different questions: a first-publication
 * year belongs to the work, an ISBN never does.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolvedWorkIdentity(
        @JsonAlias("original_title") String originalTitle,
        @JsonAlias("original_author") String originalAuthor,
        @JsonAlias("original_language") String originalLanguage,
        // The title, author and language as they appear on THIS release — for a translation these
        // differ from the original and are what the book record should actually carry. Filled from a
        // real edition page; null when the agent only pinned the work down, not the specific release.
        @JsonAlias("edition_title") String editionTitle,
        @JsonAlias("edition_author") String editionAuthor,
        @JsonAlias("edition_language") String editionLanguage,
        @JsonAlias("first_published_year") Integer firstPublishedYear,
        @JsonAlias("goodreads_url") String goodreadsUrl,
        @JsonAlias("reported_rating") Double reportedRating,
        String description,
        @JsonAlias("description_language") String descriptionLanguage,
        @JsonAlias("description_source_url") String descriptionSourceUrl,
        String publisher,
        @JsonAlias("published_date") String publishedDate,
        String isbn13,
        String isbn10,
        @JsonAlias("page_count") Integer pageCount,
        @JsonAlias("series_name") String seriesName,
        @JsonAlias("series_number") Float seriesNumber,
        @JsonAlias("series_total") Integer seriesTotal,
        List<String> genres,
        List<String> sources
) {
}
