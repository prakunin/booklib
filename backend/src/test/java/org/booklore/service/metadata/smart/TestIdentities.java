package org.booklore.service.metadata.smart;

import org.booklore.model.dto.smart.ResolvedWorkIdentity;

import java.util.List;

/**
 * Builds resolved identities for tests without repeating the record's full argument list, so adding
 * a field to {@link ResolvedWorkIdentity} does not mean editing every test that mentions one.
 */
final class TestIdentities {

    static final String GOODREADS_URL = "https://www.goodreads.com/book/show/104595.Montaigne_s_Travel_Journal";
    static final String DESCRIPTION_URL = "https://www.labirint.ru/books/700000/";

    private TestIdentities() {
    }

    /**
     * The Montaigne case the feature was built against: a Russian translation of a French work,
     * with a description quoted from a Russian bookshop page.
     */
    static ResolvedWorkIdentity identity(Double reportedRating) {
        return builder().reportedRating(reportedRating).build();
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private String originalTitle = "Journal de voyage";
        private String originalAuthor = "Michel de Montaigne";
        private String originalLanguage = "fr";
        private String editionTitle;
        private String editionAuthor;
        private String editionLanguage;
        private Integer firstPublishedYear = 1774;
        private String goodreadsUrl = GOODREADS_URL;
        private Double reportedRating;
        private String description = "Дословная аннотация издателя.";
        private String descriptionLanguage = "ru";
        private String descriptionSourceUrl = DESCRIPTION_URL;
        private String publisher;
        private String publishedDate;
        private String isbn13;
        private String isbn10;
        private Integer pageCount;
        private String seriesName;
        private Float seriesNumber;
        private Integer seriesTotal;
        private List<String> genres;
        private List<String> sources = List.of(GOODREADS_URL);

        Builder originalTitle(String value) {
            this.originalTitle = value;
            return this;
        }

        Builder originalAuthor(String value) {
            this.originalAuthor = value;
            return this;
        }

        Builder originalLanguage(String value) {
            this.originalLanguage = value;
            return this;
        }

        Builder editionTitle(String value) {
            this.editionTitle = value;
            return this;
        }

        Builder editionAuthor(String value) {
            this.editionAuthor = value;
            return this;
        }

        Builder editionLanguage(String value) {
            this.editionLanguage = value;
            return this;
        }

        Builder goodreadsUrl(String value) {
            this.goodreadsUrl = value;
            return this;
        }

        Builder reportedRating(Double value) {
            this.reportedRating = value;
            return this;
        }

        Builder description(String value) {
            this.description = value;
            return this;
        }

        Builder descriptionSourceUrl(String value) {
            this.descriptionSourceUrl = value;
            return this;
        }

        Builder publisher(String value) {
            this.publisher = value;
            return this;
        }

        Builder publishedDate(String value) {
            this.publishedDate = value;
            return this;
        }

        Builder isbn13(String value) {
            this.isbn13 = value;
            return this;
        }

        Builder isbn10(String value) {
            this.isbn10 = value;
            return this;
        }

        Builder pageCount(Integer value) {
            this.pageCount = value;
            return this;
        }

        Builder seriesName(String value) {
            this.seriesName = value;
            return this;
        }

        Builder seriesNumber(Float value) {
            this.seriesNumber = value;
            return this;
        }

        Builder seriesTotal(Integer value) {
            this.seriesTotal = value;
            return this;
        }

        Builder genres(List<String> value) {
            this.genres = value;
            return this;
        }

        Builder sources(List<String> value) {
            this.sources = value;
            return this;
        }

        ResolvedWorkIdentity build() {
            return new ResolvedWorkIdentity(originalTitle, originalAuthor, originalLanguage,
                    editionTitle, editionAuthor, editionLanguage, firstPublishedYear,
                    goodreadsUrl, reportedRating, description, descriptionLanguage, descriptionSourceUrl,
                    publisher, publishedDate, isbn13, isbn10, pageCount, seriesName, seriesNumber, seriesTotal,
                    genres, sources);
        }
    }
}
