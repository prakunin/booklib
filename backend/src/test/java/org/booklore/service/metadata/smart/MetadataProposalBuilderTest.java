package org.booklore.service.metadata.smart;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.smart.MetadataFieldProposal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataProposalBuilderTest {

    private final MetadataProposalBuilder builder = new MetadataProposalBuilder();

    private Book russianTranslation() {
        return Book.builder()
                .id(1L)
                .metadata(BookMetadata.builder()
                        .title("Путевой дневник. Путешествие Мишеля де Монтеня в Германию и Италию")
                        .authors(List.of("Монтень Мишель"))
                        .language("ru")
                        .build())
                .build();
    }

    private Book frenchOriginal() {
        return Book.builder()
                .id(2L)
                .metadata(BookMetadata.builder()
                        .title("journal-de-voyage_ocr_final")
                        .language("fr")
                        .build())
                .build();
    }

    private Optional<MetadataFieldProposal> proposal(List<MetadataFieldProposal> proposals, String field) {
        return proposals.stream().filter(p -> p.field().equals(field)).findFirst();
    }

    @Nested
    class EditionFields {

        @Test
        void proposePublisherDateAndPageCount() {
            List<MetadataFieldProposal> proposals = builder.build(russianTranslation(), TestIdentities.builder()
                    .publisher("Азбука")
                    .publishedDate("2020-09-01")
                    .pageCount(384)
                    .build(), null);

            assertThat(proposal(proposals, "publisher").orElseThrow().proposedValue()).isEqualTo("Азбука");
            assertThat(proposal(proposals, "publishedDate").orElseThrow().proposedValue()).isEqualTo("2020-09-01");
            assertThat(proposal(proposals, "pageCount").orElseThrow().proposedValue()).isEqualTo("384");
        }

        @Test
        void expandABareYearToTheFirstOfJanuary() {
            List<MetadataFieldProposal> proposals = builder.build(russianTranslation(),
                    TestIdentities.builder().publishedDate("2020").build(), null);

            assertThat(proposal(proposals, "publishedDate").orElseThrow().proposedValue()).isEqualTo("2020-01-01");
        }

        // A date the agent could not read is worth nothing; one it invented looks identical in the
        // form, so anything unparseable or impossible is dropped rather than shown.
        @Test
        void dropUnparseableAndFutureDates() {
            assertThat(builder.build(russianTranslation(),
                    TestIdentities.builder().publishedDate("сентябрь 2020").build(), null))
                    .noneMatch(p -> p.field().equals("publishedDate"));

            String farFuture = String.valueOf(LocalDate.now().getYear() + 5);
            assertThat(builder.build(russianTranslation(),
                    TestIdentities.builder().publishedDate(farFuture).build(), null))
                    .noneMatch(p -> p.field().equals("publishedDate"));
        }

        @Test
        void dropAnImplausiblePageCount() {
            assertThat(builder.build(russianTranslation(), TestIdentities.builder().pageCount(900_000).build(), null))
                    .noneMatch(p -> p.field().equals("pageCount"));
            assertThat(builder.build(russianTranslation(), TestIdentities.builder().pageCount(0).build(), null))
                    .noneMatch(p -> p.field().equals("pageCount"));
        }
    }

    @Nested
    class Isbns {

        @Test
        void acceptAValidNumberAndStripSeparators() {
            List<MetadataFieldProposal> proposals = builder.build(russianTranslation(), TestIdentities.builder()
                    .isbn13("978-5-389-18120-5")
                    .isbn10("5-389-18120-4")
                    .build(), null);

            assertThat(proposal(proposals, "isbn13").orElseThrow().proposedValue()).isEqualTo("9785389181205");
            assertThat(proposal(proposals, "isbn10").orElseThrow().proposedValue()).isEqualTo("5389181204");
        }

        // The one agent-supplied field whose fabrication is cheaply detectable: a made-up number
        // almost never satisfies its own check digit.
        @Test
        void rejectANumberWhoseCheckDigitDoesNotAddUp() {
            assertThat(builder.build(russianTranslation(), TestIdentities.builder().isbn13("9785389181204").build(), null))
                    .noneMatch(p -> p.field().equals("isbn13"));
            assertThat(builder.build(russianTranslation(), TestIdentities.builder().isbn10("5389181209").build(), null))
                    .noneMatch(p -> p.field().equals("isbn10"));
        }

        @Test
        void rejectAWrongLengthNumber() {
            assertThat(builder.build(russianTranslation(), TestIdentities.builder().isbn13("97853891812").build(), null))
                    .noneMatch(p -> p.field().equals("isbn13"));
        }
    }

    @Nested
    class WorkFields {

        // Replacing a Russian title with the French original would lose the edition the user owns.
        @Test
        void doNotProposeTheOriginalTitleForATranslation() {
            List<MetadataFieldProposal> proposals = builder.build(russianTranslation(), TestIdentities.builder().build(), null);

            assertThat(proposals).noneMatch(p -> p.field().equals("title"));
            assertThat(proposals).noneMatch(p -> p.field().equals("authors"));
        }

        // Same field, opposite conclusion: for a file already in the original language, the reported
        // title is the fix for the mangled name a digitised copy carries.
        @Test
        void proposeTheOriginalTitleWhenTheFileIsInTheOriginalLanguage() {
            List<MetadataFieldProposal> proposals = builder.build(frenchOriginal(), TestIdentities.builder().build(), null);

            assertThat(proposal(proposals, "title").orElseThrow().proposedValue()).isEqualTo("Journal de voyage");
            assertThat(proposal(proposals, "authors").orElseThrow().proposedValue()).isEqualTo("Michel de Montaigne");
        }

        @Test
        void leaveAnExistingAuthorListAlone() {
            Book withAuthors = Book.builder()
                    .id(3L)
                    .metadata(BookMetadata.builder()
                            .title("Journal de voyage")
                            .language("fr")
                            .authors(List.of("Michel de Montaigne", "Louis Lautrey"))
                            .build())
                    .build();

            assertThat(builder.build(withAuthors, TestIdentities.builder().build(), null))
                    .noneMatch(p -> p.field().equals("authors"));
        }

        @Test
        void proposeReplacingADifferentSingletonAuthor() {
            Book withBadAuthor = Book.builder()
                    .id(6L)
                    .metadata(BookMetadata.builder()
                            .authors(List.of("AndreyKr"))
                            .build())
                    .build();

            MetadataFieldProposal authors = proposal(builder.build(withBadAuthor, TestIdentities.builder()
                    .editionAuthor("Анатолий Бурак")
                    .build(), null), "authors").orElseThrow();

            assertThat(authors.currentValue()).isEqualTo("AndreyKr");
            assertThat(authors.proposedValue()).isEqualTo("Анатолий Бурак");
            assertThat(authors.locked()).isFalse();
        }

        @Test
        void doNotProposeAMatchingSingletonAuthor() {
            Book withMatchingAuthor = Book.builder()
                    .id(7L)
                    .metadata(BookMetadata.builder()
                            .authors(List.of("  анатолий бурак "))
                            .build())
                    .build();

            assertThat(builder.build(withMatchingAuthor, TestIdentities.builder()
                    .editionAuthor("Анатолий Бурак")
                    .build(), null))
                    .noneMatch(p -> p.field().equals("authors"));
        }

        @Test
        void showButLockASingletonAuthorReplacementWhenAuthorsAreLocked() {
            Book withLockedAuthor = Book.builder()
                    .id(8L)
                    .metadata(BookMetadata.builder()
                            .authors(List.of("AndreyKr"))
                            .authorsLocked(true)
                            .build())
                    .build();

            MetadataFieldProposal authors = proposal(builder.build(withLockedAuthor, TestIdentities.builder()
                    .editionAuthor("Анатолий Бурак")
                    .build(), null), "authors").orElseThrow();

            assertThat(authors.currentValue()).isEqualTo("AndreyKr");
            assertThat(authors.proposedValue()).isEqualTo("Анатолий Бурак");
            assertThat(authors.locked()).isTrue();
        }

        // The common case the feature exists for: an FB2 with empty internals. Edition fields (the
        // release's own title/author/language) fill the blanks with the right, same-language values.
        @Test
        void fillEmptyTitleAuthorAndLanguageFromTheEdition() {
            List<MetadataFieldProposal> proposals = builder.build(emptyBook(), TestIdentities.builder()
                    .editionTitle("Рассвет рыцаря")
                    .editionAuthor("Хантер")
                    .editionLanguage("ru")
                    .build(), null);

            assertThat(proposal(proposals, "title").orElseThrow().proposedValue()).isEqualTo("Рассвет рыцаря");
            assertThat(proposal(proposals, "authors").orElseThrow().proposedValue()).isEqualTo("Хантер");
            assertThat(proposal(proposals, "language").orElseThrow().proposedValue()).isEqualTo("ru");
        }

        // With a blank title there is nothing to lose, so even the original title beats leaving it empty.
        @Test
        void fillAnEmptyTitleFromTheOriginalWhenNoEditionTitle() {
            List<MetadataFieldProposal> proposals = builder.build(emptyBook(), TestIdentities.builder().build(), null);

            assertThat(proposal(proposals, "title").orElseThrow().proposedValue()).isEqualTo("Journal de voyage");
        }

        // The edition's own author wins over the original when both are known.
        @Test
        void prefersTheEditionAuthorOverTheOriginal() {
            List<MetadataFieldProposal> proposals = builder.build(emptyBook(),
                    TestIdentities.builder().editionAuthor("Монтень Мишель").build(), null);

            assertThat(proposal(proposals, "authors").orElseThrow().proposedValue()).isEqualTo("Монтень Мишель");
        }

        // No edition language, but the agent quoted a Russian description — good enough to fill a blank.
        @Test
        void fallsBackToTheDescriptionLanguageForAnEmptyLanguage() {
            List<MetadataFieldProposal> proposals = builder.build(emptyBook(),
                    TestIdentities.builder().editionLanguage(null).build(), null);

            assertThat(proposal(proposals, "language").orElseThrow().proposedValue()).isEqualTo("ru");
        }

        // A language that is already set is never touched.
        @Test
        void leaveAnExistingLanguageAlone() {
            assertThat(builder.build(russianTranslation(), TestIdentities.builder().editionLanguage("en").build(), null))
                    .noneMatch(p -> p.field().equals("language"));
        }
    }

    private Book emptyBook() {
        return Book.builder().id(9L).metadata(BookMetadata.builder().build()).build();
    }

    @Nested
    class SeriesAndGenres {

        @Test
        void proposeSeriesFieldsTogether() {
            List<MetadataFieldProposal> proposals = builder.build(russianTranslation(), TestIdentities.builder()
                    .seriesName("Азбука-классика")
                    .seriesNumber(2f)
                    .seriesTotal(7)
                    .build(), null);

            assertThat(proposal(proposals, "seriesName").orElseThrow().proposedValue()).isEqualTo("Азбука-классика");
            assertThat(proposal(proposals, "seriesNumber")).isPresent();
            assertThat(proposal(proposals, "seriesTotal")).isPresent();
        }

        // "Book 2 of nothing" is not a metadata improvement.
        @Test
        void dropASeriesNumberWithoutASeriesName() {
            assertThat(builder.build(russianTranslation(), TestIdentities.builder().seriesNumber(2f).build(), null))
                    .noneMatch(p -> p.field().startsWith("series"));
        }

        @Test
        void capGenresAndShowTheCurrentOnesBeside() {
            Book withCategories = Book.builder()
                    .id(4L)
                    .metadata(BookMetadata.builder()
                            .language("ru")
                            .categories(Set.of("antique_european"))
                            .build())
                    .build();

            MetadataFieldProposal categories = proposal(builder.build(withCategories, TestIdentities.builder()
                    .genres(List.of("Travel", "Memoir", "Essays", "Philosophy", "History", "Renaissance"))
                    .build(), null), "categories").orElseThrow();

            assertThat(categories.proposedValue()).isEqualTo("Travel, Memoir, Essays, Philosophy, History");
            assertThat(categories.currentValue()).isEqualTo("antique_european");
        }
    }

    @Nested
    class LockedFields {

        // Hiding a locked field would make the run look like it found nothing for it.
        @Test
        void areProposedButFlagged() {
            Book locked = Book.builder()
                    .id(5L)
                    .metadata(BookMetadata.builder()
                            .language("ru")
                            .publisher("Ручной издатель")
                            .publisherLocked(true)
                            .build())
                    .build();

            MetadataFieldProposal publisher =
                    proposal(builder.build(locked, TestIdentities.builder().publisher("Азбука").build(), null), "publisher")
                            .orElseThrow();

            assertThat(publisher.locked()).isTrue();
            assertThat(publisher.currentValue()).isEqualTo("Ручной издатель");
        }
    }
}
