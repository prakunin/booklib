package org.booklore.service.metadata.smart;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.smart.MetadataFieldProposal;
import org.booklore.model.dto.smart.ResolvedWorkIdentity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a resolved identity into the list of field changes offered to the user.
 * <p>
 * Everything the agent reports passes a structural check first. A parser cannot confirm a
 * publisher's name or a page count the way {@code GoodReadsParser} confirms a rating, so the only
 * defence left for those fields is shape: an ISBN whose check digit does not add up, a year in the
 * future, or a page count in the millions is a fabrication that looks like data, and it is dropped
 * here rather than shown as a plausible suggestion.
 */
@Slf4j
@Component
public class MetadataProposalBuilder {

    private static final String AGENT_SOURCE = "Agent";
    private static final int MAX_GENRES = 5;
    private static final int MAX_PAGE_COUNT = 20_000;
    private static final int EARLIEST_YEAR = 1000;

    public List<MetadataFieldProposal> build(Book book, ResolvedWorkIdentity identity, BookMetadata verified) {
        BookMetadata current = book.getMetadata();
        List<MetadataFieldProposal> proposals = new ArrayList<>();

        addWorkProposals(proposals, current, identity);
        addDescription(proposals, current, identity);
        addEditionProposals(proposals, current, identity);
        addSeries(proposals, current, identity);
        addGenres(proposals, current, identity);
        addVerifiedGoodreads(proposals, current, identity, verified);

        return proposals;
    }

    /**
     * Title, author and language of the book record itself.
     * <p>
     * The value we want is the <em>edition's</em>, in the file's own language — the agent supplies it
     * as {@code edition_*}. The original is only a fallback, and a dangerous one for a title: writing
     * the English original over a filled-in Russian title loses the edition the user owns. So the
     * original title is used only when the field is empty (nothing to lose) or the file already is
     * the original-language edition; the edition title, being same-language, is always safe.
     */
    private void addWorkProposals(List<MetadataFieldProposal> proposals, BookMetadata current, ResolvedWorkIdentity identity) {
        addTitleProposal(proposals, current, identity);
        addAuthorProposal(proposals, current, identity);
        addLanguageProposal(proposals, current, identity);
    }

    private void addTitleProposal(List<MetadataFieldProposal> proposals, BookMetadata current,
                                  ResolvedWorkIdentity identity) {
        String currentTitle = current == null ? null : current.getTitle();
        String editionTitle = identity.editionTitle();
        String preferredTitle = isUsable(editionTitle) ? editionTitle : identity.originalTitle();
        boolean titleEmpty = !isUsable(currentTitle);
        boolean titleSafe = isUsable(editionTitle) || isOriginalLanguageEdition(current, identity);
        if (isUsable(preferredTitle) && (titleEmpty || titleSafe) && !preferredTitle.equals(currentTitle)) {
            proposals.add(proposal("title", currentTitle, preferredTitle.strip(), AGENT_SOURCE,
                    firstSource(identity), locked(current == null ? null : current.getTitleLocked())));
        }

    }

    private void addAuthorProposal(List<MetadataFieldProposal> proposals, BookMetadata current,
                                   ResolvedWorkIdentity identity) {
        // A single bad extractor value (often an uploader nickname) must not suppress a better,
        // reviewable identity. Multiple names may be co-authors or translators, so never collapse
        // that list to the agent's one reported name.
        List<String> currentAuthors =
                current == null || current.getAuthors() == null ? List.of() : current.getAuthors();
        boolean hasEditionAuthor = isUsable(identity.editionAuthor());
        String preferredAuthor = hasEditionAuthor ? identity.editionAuthor() : identity.originalAuthor();
        boolean safeToReplace = currentAuthors.isEmpty() || hasEditionAuthor;
        if (currentAuthors.size() <= 1 && safeToReplace && isUsable(preferredAuthor)) {
            String proposedAuthor = preferredAuthor.strip();
            String currentAuthor = currentAuthors.isEmpty() ? null : currentAuthors.getFirst();
            boolean alreadyMatches = isUsable(currentAuthor)
                    && proposedAuthor.equalsIgnoreCase(currentAuthor.strip());
            if (!alreadyMatches) {
                proposals.add(proposal("authors", currentAuthor, proposedAuthor, AGENT_SOURCE,
                        firstSource(identity), locked(current == null ? null : current.getAuthorsLocked())));
            }
        }

    }

    private void addLanguageProposal(List<MetadataFieldProposal> proposals, BookMetadata current,
                                     ResolvedWorkIdentity identity) {
        // Fill the language only when it is missing; the edition's language is the right one, with the
        // quoted description's language as a fallback since the agent read that page in it.
        String currentLanguage = current == null ? null : current.getLanguage();
        String preferredLanguage = firstNonBlank(identity.editionLanguage(), identity.descriptionLanguage());
        if (!isUsable(currentLanguage) && isUsable(preferredLanguage)) {
            proposals.add(proposal("language", null, preferredLanguage.strip().toLowerCase(), AGENT_SOURCE,
                    firstSource(identity), locked(current == null ? null : current.getLanguageLocked())));
        }
    }

    private String firstNonBlank(String first, String second) {
        if (isUsable(first)) {
            return first;
        }
        return isUsable(second) ? second : null;
    }

    private void addDescription(List<MetadataFieldProposal> proposals, BookMetadata current, ResolvedWorkIdentity identity) {
        if (!isUsable(identity.description())) {
            return;
        }
        proposals.add(proposal(
                "description",
                current == null ? null : current.getDescription(),
                identity.description(),
                identity.descriptionLanguage() == null ? AGENT_SOURCE : AGENT_SOURCE + " (" + identity.descriptionLanguage() + ")",
                identity.descriptionSourceUrl(),
                locked(current == null ? null : current.getDescriptionLocked())));
    }

    private void addEditionProposals(List<MetadataFieldProposal> proposals, BookMetadata current, ResolvedWorkIdentity identity) {
        addPublisherProposal(proposals, current, identity);
        addPublishedDateProposal(proposals, current, identity);
        addIsbnProposals(proposals, current, identity);
        addPageCountProposal(proposals, current, identity);
    }

    private void addPublisherProposal(List<MetadataFieldProposal> proposals, BookMetadata current,
                                      ResolvedWorkIdentity identity) {
        if (isUsable(identity.publisher())) {
            proposals.add(proposal("publisher", current == null ? null : current.getPublisher(),
                    identity.publisher().strip(), AGENT_SOURCE, firstSource(identity),
                    locked(current == null ? null : current.getPublisherLocked())));
        }
    }

    private void addPublishedDateProposal(List<MetadataFieldProposal> proposals, BookMetadata current,
                                          ResolvedWorkIdentity identity) {
        String publishedDate = normalizePublishedDate(identity.publishedDate());
        if (publishedDate != null) {
            proposals.add(proposal("publishedDate",
                    current == null || current.getPublishedDate() == null ? null : current.getPublishedDate().toString(),
                    publishedDate, AGENT_SOURCE, firstSource(identity),
                    locked(current == null ? null : current.getPublishedDateLocked())));
        }
    }

    private void addIsbnProposals(List<MetadataFieldProposal> proposals, BookMetadata current,
                                  ResolvedWorkIdentity identity) {
        String isbn13 = normalizeIsbn(identity.isbn13(), 13);
        if (isbn13 != null) {
            proposals.add(proposal("isbn13", current == null ? null : current.getIsbn13(), isbn13,
                    AGENT_SOURCE, firstSource(identity), locked(current == null ? null : current.getIsbn13Locked())));
        }
        String isbn10 = normalizeIsbn(identity.isbn10(), 10);
        if (isbn10 != null) {
            proposals.add(proposal("isbn10", current == null ? null : current.getIsbn10(), isbn10,
                    AGENT_SOURCE, firstSource(identity), locked(current == null ? null : current.getIsbn10Locked())));
        }
    }

    private void addPageCountProposal(List<MetadataFieldProposal> proposals, BookMetadata current,
                                      ResolvedWorkIdentity identity) {
        Integer pageCount = identity.pageCount();
        if (pageCount != null && pageCount > 0 && pageCount <= MAX_PAGE_COUNT) {
            proposals.add(proposal("pageCount",
                    current == null || current.getPageCount() == null ? null : current.getPageCount().toString(),
                    pageCount.toString(), AGENT_SOURCE, firstSource(identity),
                    locked(current == null ? null : current.getPageCountLocked())));
        }
    }

    private void addSeries(List<MetadataFieldProposal> proposals, BookMetadata current, ResolvedWorkIdentity identity) {
        if (!isUsable(identity.seriesName())) {
            // A series number without a series name has nothing to attach to, so both are dropped
            // together rather than leaving "book 2 of nothing" in the form.
            return;
        }
        proposals.add(proposal("seriesName", current == null ? null : current.getSeriesName(),
                identity.seriesName().strip(), AGENT_SOURCE, firstSource(identity),
                locked(current == null ? null : current.getSeriesNameLocked())));

        addSeriesNumberProposal(proposals, current, identity);
        addSeriesTotalProposal(proposals, current, identity);
    }

    private void addSeriesNumberProposal(List<MetadataFieldProposal> proposals, BookMetadata current,
                                         ResolvedWorkIdentity identity) {
        Float seriesNumber = identity.seriesNumber();
        if (seriesNumber != null && seriesNumber > 0 && seriesNumber < 10_000) {
            proposals.add(proposal("seriesNumber",
                    current == null || current.getSeriesNumber() == null ? null : current.getSeriesNumber().toString(),
                    seriesNumber.toString(), AGENT_SOURCE, firstSource(identity),
                    locked(current == null ? null : current.getSeriesNumberLocked())));
        }
    }

    private void addSeriesTotalProposal(List<MetadataFieldProposal> proposals, BookMetadata current,
                                        ResolvedWorkIdentity identity) {
        Integer seriesTotal = identity.seriesTotal();
        if (seriesTotal != null && seriesTotal > 0 && seriesTotal <= 1_000) {
            proposals.add(proposal("seriesTotal",
                    current == null || current.getSeriesTotal() == null ? null : current.getSeriesTotal().toString(),
                    seriesTotal.toString(), AGENT_SOURCE, firstSource(identity),
                    locked(current == null ? null : current.getSeriesTotalLocked())));
        }
    }

    private void addGenres(List<MetadataFieldProposal> proposals, BookMetadata current, ResolvedWorkIdentity identity) {
        if (identity.genres() == null || identity.genres().isEmpty()) {
            return;
        }
        List<String> genres = identity.genres().stream()
                .filter(this::isUsable)
                .map(String::strip)
                .filter(genre -> genre.length() <= 64)
                .distinct()
                .limit(MAX_GENRES)
                .toList();
        if (genres.isEmpty()) {
            return;
        }
        Set<String> currentCategories = current == null ? null : current.getCategories();
        proposals.add(proposal("categories",
                currentCategories == null || currentCategories.isEmpty() ? null : String.join(", ", currentCategories),
                String.join(", ", genres), AGENT_SOURCE, firstSource(identity),
                locked(current == null ? null : current.getCategoriesLocked())));
    }

    /**
     * The Goodreads id and rating are the only values here the agent did not supply: they come back
     * from the parser after the id it pointed at was re-fetched and checked.
     */
    private void addVerifiedGoodreads(List<MetadataFieldProposal> proposals, BookMetadata current,
                                      ResolvedWorkIdentity identity, BookMetadata verified) {
        if (verified == null) {
            return;
        }
        if (verified.getGoodreadsId() != null) {
            proposals.add(proposal("goodreadsId", current == null ? null : current.getGoodreadsId(),
                    verified.getGoodreadsId(), "Goodreads", identity.goodreadsUrl(), false));
        }
        if (verified.getGoodreadsRating() != null) {
            proposals.add(proposal("goodreadsRating",
                    current == null || current.getGoodreadsRating() == null ? null : current.getGoodreadsRating().toString(),
                    verified.getGoodreadsRating().toString(), "Goodreads (verified)", identity.goodreadsUrl(),
                    locked(current == null ? null : current.getGoodreadsRatingLocked())));
        }
    }

    private boolean isOriginalLanguageEdition(BookMetadata current, ResolvedWorkIdentity identity) {
        String language = current == null ? null : current.getLanguage();
        return isUsable(language)
                && isUsable(identity.originalLanguage())
                && language.strip().equalsIgnoreCase(identity.originalLanguage().strip());
    }

    /**
     * Accepts a full date or a bare year, and rejects anything outside the range a book can carry.
     * A year alone becomes 1 January, matching how the metadata providers store partial dates.
     */
    private String normalizePublishedDate(String reported) {
        if (!isUsable(reported)) {
            return null;
        }
        String value = reported.strip();
        try {
            LocalDate date = value.matches("\\d{4}")
                    ? LocalDate.of(Integer.parseInt(value), Month.JANUARY, 1)
                    : LocalDate.parse(value);
            if (date.getYear() < EARLIEST_YEAR
                    || date.getYear() > Year.now(ZoneId.systemDefault()).getValue() + 1) {
                log.debug("Discarding out-of-range published date '{}' from the agent", reported);
                return null;
            }
            return date.toString();
        } catch (DateTimeParseException | NumberFormatException _) {
            log.debug("Discarding unparseable published date '{}' from the agent", reported);
            return null;
        }
    }

    /**
     * Strips separators, then verifies the check digit. This is the one field where a fabricated
     * value is cheaply detectable — a made-up ISBN almost never satisfies its own checksum.
     */
    private String normalizeIsbn(String reported, int expectedLength) {
        if (!isUsable(reported)) {
            return null;
        }
        String digits = reported.replaceAll("[\\s-]", "").toUpperCase();
        if (digits.length() != expectedLength) {
            return null;
        }
        boolean valid = expectedLength == 13 ? isValidIsbn13(digits) : isValidIsbn10(digits);
        if (!valid) {
            log.debug("Discarding ISBN '{}' from the agent: check digit does not match", reported);
            return null;
        }
        return digits;
    }

    private boolean isValidIsbn13(String isbn) {
        if (!isbn.matches("\\d{13}")) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < 13; i++) {
            total += (isbn.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return total % 10 == 0;
    }

    private boolean isValidIsbn10(String isbn) {
        if (!isbn.matches("\\d{9}[\\dX]")) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < 9; i++) {
            total += (isbn.charAt(i) - '0') * (10 - i);
        }
        char last = isbn.charAt(9);
        total += last == 'X' ? 10 : last - '0';
        return total % 11 == 0;
    }

    private String firstSource(ResolvedWorkIdentity identity) {
        if (identity.sources() == null || identity.sources().isEmpty()) {
            return identity.goodreadsUrl();
        }
        return identity.sources().getFirst();
    }

    private MetadataFieldProposal proposal(String field, String currentValue, String proposedValue,
                                           String source, String sourceUrl, boolean locked) {
        return new MetadataFieldProposal(field, currentValue, proposedValue, source, sourceUrl, locked);
    }

    private boolean isUsable(String value) {
        return value != null && !value.isBlank();
    }

    private boolean locked(Boolean lockedFlag) {
        return Boolean.TRUE.equals(lockedFlag);
    }
}
