package org.booklore.model.dto.smart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

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
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResolvedWorkIdentity(
        String originalTitle,
        String originalAuthor,
        String originalLanguage,
        // The title, author and language as they appear on THIS release — for a translation these
        // differ from the original and are what the book record should actually carry. Filled from a
        // real edition page; null when the agent only pinned the work down, not the specific release.
        String editionTitle,
        String editionAuthor,
        String editionLanguage,
        Integer firstPublishedYear,
        String goodreadsUrl,
        Double reportedRating,
        String description,
        String descriptionLanguage,
        String descriptionSourceUrl,
        String publisher,
        String publishedDate,
        String isbn13,
        String isbn10,
        Integer pageCount,
        String seriesName,
        Float seriesNumber,
        Integer seriesTotal,
        List<String> genres,
        List<String> sources
) {
}
