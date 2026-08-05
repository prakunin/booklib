package org.booklore.service.metadata;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.booklore.mapper.BookMetadataMapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.BookMetadataFieldSourceEntity;
import org.booklore.model.entity.MetadataFetchProposalEntity;
import org.booklore.model.enums.MetadataField;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookMetadataFieldSourceRepository;
import org.booklore.repository.BookMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetadataProposalProvenanceServiceTest {

    @Mock
    private BookMetadataRepository bookMetadataRepository;
    @Mock
    private BookMetadataMapper bookMetadataMapper;
    @Mock
    private BookMetadataFieldSourceRepository fieldSourceRepository;

    private ObjectMapper objectMapper;
    private MetadataProposalProvenanceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new MetadataProposalProvenanceService(
                objectMapper, bookMetadataRepository, bookMetadataMapper, fieldSourceRepository);
    }

    private static Map<MetadataField, MetadataProvider> providers(Object... pairs) {
        EnumMap<MetadataField, MetadataProvider> map = new EnumMap<>(MetadataField.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((MetadataField) pairs[i], (MetadataProvider) pairs[i + 1]);
        }
        return map;
    }

    @Nested
    @DisplayName("Building the proposal")
    class DescribeChanges {

        @Test
        void keepsTheProviderOfEveryFieldTheProposalWouldChange() {
            BookMetadata proposed = BookMetadata.builder()
                    .title("The Fetched Title")
                    .isbn13("9780000000001")
                    .fieldProviders(providers(
                            MetadataField.TITLE, MetadataProvider.GoodReads,
                            MetadataField.ISBN_13, MetadataProvider.FlibustaLocal))
                    .build();
            BookMetadata existing = BookMetadata.builder().title("The Old Title").build();

            Map<MetadataField, MetadataProvider> stored = objectMapper.readValue(
                    service.describeChanges(proposed, existing),
                    new TypeReference<Map<MetadataField, MetadataProvider>>() {
                    });

            assertThat(stored).containsExactlyInAnyOrderEntriesOf(providers(
                    MetadataField.TITLE, MetadataProvider.GoodReads,
                    MetadataField.ISBN_13, MetadataProvider.FlibustaLocal));
        }

        @Test
        void dropsAFieldWhoseProviderMerelyAgreedWithWhatWasAlreadyThere() {
            BookMetadata proposed = BookMetadata.builder()
                    .title("Same Title")
                    .fieldProviders(providers(MetadataField.TITLE, MetadataProvider.GoodReads))
                    .build();
            BookMetadata existing = BookMetadata.builder().title("Same Title").build();

            // The direct path refuses to attribute agreement because it cannot be told apart from the
            // user having typed what the provider would have said. The proposal path must not differ.
            assertThat(service.describeChanges(proposed, existing)).isNull();
        }

        @Test
        void storesNothingWhenTheMergeAttributedNothing() {
            BookMetadata proposed = BookMetadata.builder().title("A Title").build();

            assertThat(service.describeChanges(proposed, BookMetadata.builder().build())).isNull();
        }

        @Test
        void storesNothingWhenThePreviousStateCouldNotBeRead() {
            BookMetadata proposed = BookMetadata.builder()
                    .title("A Title")
                    .fieldProviders(providers(MetadataField.TITLE, MetadataProvider.GoodReads))
                    .build();

            assertThat(service.describeChanges(proposed, null)).isNull();
        }

        @Test
        void treatsAFirstEverValueAsAChange() {
            BookMetadata proposed = BookMetadata.builder()
                    .publisher("Gollancz")
                    .fieldProviders(providers(MetadataField.PUBLISHER, MetadataProvider.GoodReads))
                    .build();

            assertThat(service.describeChanges(proposed, BookMetadata.builder().build()))
                    .contains("PUBLISHER")
                    .contains("GoodReads");
        }
    }

    @Nested
    @DisplayName("Accepting the proposal")
    class RecordAcceptedProposal {

        private MetadataFetchProposalEntity proposal(String metadataJson, String providersJson) {
            return MetadataFetchProposalEntity.builder()
                    .proposalId(1L)
                    .bookId(7L)
                    .metadataJson(metadataJson)
                    .fieldProvidersJson(providersJson)
                    .build();
        }

        /**
         * Stubs what {@code book_metadata} holds at the moment the ACCEPT is processed.
         * <p>
         * This method decides which side of the client's PUT the accept lands on, and that ordering is
         * the client's to guarantee, not this service's — see
         * {@code metadata-review-dialog-component.spec.ts}, which is what actually pins it. What is
         * pinned here is that the service is safe on both sides: fed post-PUT state it attributes, fed
         * pre-PUT state it files nothing rather than something wrong.
         */
        private void bookNowHolds(BookMetadata stored) {
            BookMetadataEntity entity = new BookMetadataEntity();
            when(bookMetadataRepository.findById(7L)).thenReturn(Optional.of(entity));
            when(bookMetadataMapper.toBookMetadata(any(BookMetadataEntity.class), anyBoolean())).thenReturn(stored);
        }

        @Test
        void filesARowForEachAcceptedValueThatActuallyLanded() {
            String metadataJson = objectMapper.writeValueAsString(
                    BookMetadata.builder().title("The Fetched Title").publisher("Gollancz").build());
            bookNowHolds(BookMetadata.builder().title("The Fetched Title").publisher("Gollancz").build());

            service.recordAcceptedProposal(proposal(metadataJson,
                    "{\"TITLE\":\"GoodReads\",\"PUBLISHER\":\"FlibustaLocal\"}"));

            ArgumentCaptor<List<BookMetadataFieldSourceEntity>> saved = ArgumentCaptor.captor();
            verify(fieldSourceRepository).saveAll(saved.capture());
            assertThat(saved.getValue())
                    .extracting(BookMetadataFieldSourceEntity::getBookId,
                            BookMetadataFieldSourceEntity::getFieldName,
                            BookMetadataFieldSourceEntity::getProvider)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(7L, MetadataField.TITLE, MetadataProvider.GoodReads),
                            org.assertj.core.groups.Tuple.tuple(7L, MetadataField.PUBLISHER, MetadataProvider.FlibustaLocal));
            assertThat(saved.getValue()).allSatisfy(row -> assertThat(row.getUpdatedAt()).isNotNull());
        }

        @Test
        void attributesNothingToAFieldTheUserEditedBeforeSaving() {
            String metadataJson = objectMapper.writeValueAsString(
                    BookMetadata.builder().title("The Fetched Title").publisher("Gollancz").build());
            // The picker lets a value be edited before the accept is sent; the stored title is the
            // user's, so nothing may claim GoodReads wrote it.
            bookNowHolds(BookMetadata.builder().title("What The User Typed").publisher("Gollancz").build());

            service.recordAcceptedProposal(proposal(metadataJson,
                    "{\"TITLE\":\"GoodReads\",\"PUBLISHER\":\"FlibustaLocal\"}"));

            ArgumentCaptor<List<BookMetadataFieldSourceEntity>> saved = ArgumentCaptor.captor();
            verify(fieldSourceRepository).saveAll(saved.capture());
            assertThat(saved.getValue())
                    .extracting(BookMetadataFieldSourceEntity::getFieldName)
                    .containsExactly(MetadataField.PUBLISHER);
        }

        @Test
        void writesNothingWhenTheAcceptedValuesNeverLanded() {
            String metadataJson = objectMapper.writeValueAsString(
                    BookMetadata.builder().title("The Fetched Title").build());
            bookNowHolds(BookMetadata.builder().title("Something Else Entirely").build());

            service.recordAcceptedProposal(proposal(metadataJson, "{\"TITLE\":\"GoodReads\"}"));

            verify(fieldSourceRepository, never()).saveAll(any());
        }

        @Test
        void attributesNothingIfItIsSomehowRunBeforeTheAcceptedValuesWereWritten() {
            // The interleaving that used to happen for real: the client fired the ACCEPTED post
            // alongside the metadata PUT rather than after it, so this ran against pre-PUT state. The
            // service cannot detect that, and this is what it does when it happens — nothing, rather
            // than attributing the old value to the provider. The ordering itself is guaranteed on the
            // client and pinned by metadata-review-dialog-component.spec.ts.
            String metadataJson = objectMapper.writeValueAsString(
                    BookMetadata.builder().title("The Fetched Title").publisher("Gollancz").build());
            bookNowHolds(BookMetadata.builder().title("The Title From Before").publisher("Old Publisher").build());

            service.recordAcceptedProposal(proposal(metadataJson,
                    "{\"TITLE\":\"GoodReads\",\"PUBLISHER\":\"FlibustaLocal\"}"));

            verify(fieldSourceRepository, never()).saveAll(any());
        }

        @Test
        void doesNothingForAProposalStoredBeforeProvenanceWasCarried() {
            service.recordAcceptedProposal(proposal("{\"title\":\"Anything\"}", null));

            verifyNoInteractions(fieldSourceRepository, bookMetadataRepository);
        }

        @Test
        void survivesAProposalWhoseStoredJsonCannotBeRead() {
            service.recordAcceptedProposal(proposal("{not json", "{\"TITLE\":\"GoodReads\"}"));

            verifyNoInteractions(fieldSourceRepository);
        }

        @Test
        void doesNothingWhenTheBookHasSinceBeenDeleted() {
            String metadataJson = objectMapper.writeValueAsString(BookMetadata.builder().title("A Title").build());
            when(bookMetadataRepository.findById(7L)).thenReturn(Optional.empty());

            service.recordAcceptedProposal(proposal(metadataJson, "{\"TITLE\":\"GoodReads\"}"));

            verifyNoInteractions(fieldSourceRepository);
        }
    }
}
