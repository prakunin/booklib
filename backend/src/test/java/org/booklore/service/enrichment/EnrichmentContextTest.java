package org.booklore.service.enrichment;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentContextTest {

    private EnrichmentContext context(BookMetadata existing, EnrichmentRequest request) {
        return new EnrichmentContext(Book.builder().id(1L).metadata(existing).build(),
                7L, "a.zip", "1.fb2", request);
    }

    private EnrichmentRequest request() {
        return EnrichmentRequest.builder().scope(EnrichmentRequest.Scope.BOOK).build();
    }

    @Nested
    class StepSelection {

        @Test
        void allowsEveryStepExceptTheAgentWhenNoneAreNamed() {
            EnrichmentContext context = context(null, request());

            assertThat(context.isStepAllowed(EnrichmentStepType.LOCAL_CATALOG)).isTrue();
            assertThat(context.isStepAllowed(EnrichmentStepType.PROVIDERS)).isTrue();
            assertThat(context.isStepAllowed(EnrichmentStepType.AGENT_IDENTITY)).isFalse();
        }

        /**
         * The difference between a cheap run and one that costs minutes per book must never be
         * something a caller enables by leaving a field out.
         */
        @Test
        void requiresTheExplicitFlagEvenWhenTheAgentStepIsNamed() {
            EnrichmentContext context = context(null, EnrichmentRequest.builder()
                    .scope(EnrichmentRequest.Scope.BOOK)
                    .steps(EnumSet.of(EnrichmentStepType.AGENT_IDENTITY))
                    .build());

            assertThat(context.isStepAllowed(EnrichmentStepType.AGENT_IDENTITY)).isFalse();
        }

        @Test
        void allowsTheAgentOnlyWhenBothNamedAndFlagged() {
            EnrichmentContext context = context(null, EnrichmentRequest.builder()
                    .scope(EnrichmentRequest.Scope.BOOK)
                    .agentAllowed(true)
                    .build());

            assertThat(context.isStepAllowed(EnrichmentStepType.AGENT_IDENTITY)).isTrue();
        }
    }

    @Nested
    class Contributions {

        @Test
        void ignoresAnAbsentContribution() {
            EnrichmentContext context = context(null, request());

            context.addContribution(MetadataProvider.Amazon, null, EnrichmentConfidence.HIGH);

            assertThat(context.getContributions()).isEmpty();
        }

        @Test
        void keepsTheHighestConfidenceSeenForAProvider() {
            EnrichmentContext context = context(null, request());

            context.addContribution(MetadataProvider.Amazon, BookMetadata.builder().build(), EnrichmentConfidence.LOW);
            context.addContribution(MetadataProvider.Amazon, BookMetadata.builder().build(), EnrichmentConfidence.HIGH);
            context.addContribution(MetadataProvider.Amazon, BookMetadata.builder().build(), EnrichmentConfidence.MEDIUM);

            assertThat(context.getConfidences()).containsEntry(MetadataProvider.Amazon, EnrichmentConfidence.HIGH);
        }
    }

    /**
     * The agent step exists only to answer "which book is this", so it must not run when something
     * cheaper already answered it.
     */
    @Nested
    class IdentifierDetection {

        @Test
        void seesAnIdentifierAlreadyOnTheBook() {
            assertThat(context(BookMetadata.builder().isbn13("9785171234567").build(), request())
                    .hasIdentifier()).isTrue();
        }

        @Test
        void seesAnIdentifierAStepJustContributed() {
            EnrichmentContext context = context(BookMetadata.builder().title("Без ISBN").build(), request());
            assertThat(context.hasIdentifier()).isFalse();

            context.addContribution(MetadataProvider.Amazon,
                    BookMetadata.builder().asin("B00TEST").build(), EnrichmentConfidence.MEDIUM);

            assertThat(context.hasIdentifier()).isTrue();
        }

        @Test
        void treatsBlankIdentifiersAsAbsent() {
            assertThat(context(BookMetadata.builder().isbn13("  ").isbn10("").build(), request())
                    .hasIdentifier()).isFalse();
        }
    }
}
