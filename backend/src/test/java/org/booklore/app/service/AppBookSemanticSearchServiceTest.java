package org.booklore.app.service;

import org.booklore.config.RecommendationEmbeddingProperties;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.RecommendationEmbeddingSettings;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ContentRestrictionMode;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.repository.BookEmbeddingVectorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.repository.projection.BookEmbeddingCandidate;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.recommender.BookVectorService;
import org.booklore.service.recommender.OllamaEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppBookSemanticSearchServiceTest {

    private static final String ACTIVE_MODEL = "embeddinggemma:300m-512-v2";

    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private UserContentRestrictionRepository restrictionRepository;
    @Mock
    private BookEmbeddingVectorRepository embeddingVectorRepository;
    @Mock
    private OllamaEmbeddingClient ollamaEmbeddingClient;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AppBookSemanticSearchService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AppBookSemanticSearchService(
                authenticationService,
                bookRepository,
                restrictionRepository,
                embeddingVectorRepository,
                ollamaEmbeddingClient,
                new BookVectorService(new ObjectMapper()),
                appSettingService,
                new RecommendationEmbeddingProperties(),
                transactionManager);
        when(embeddingVectorRepository.hasEmbeddingsForModel(anyString())).thenReturn(true);
        when(ollamaEmbeddingClient.modelVersion()).thenReturn(ACTIVE_MODEL);
        when(ollamaEmbeddingClient.embedQuery(anyString())).thenReturn(new double[]{0.1, 0.2});
        mockThreshold(0.5);
    }

    @Nested
    class TransactionBoundaries {

        // The query embedding is an HTTP round trip to Ollama. A class-level @Transactional would keep a
        // pooled database connection open for its whole duration on every keystroke, so the DB work is
        // scoped explicitly instead.
        @Test
        void doesNotOpenATransactionAroundTheEmbedderCall() {
            assertThat(AppBookSemanticSearchService.class.isAnnotationPresent(Transactional.class))
                    .as("AppBookSemanticSearchService must not hold a connection while calling Ollama")
                    .isFalse();
        }
    }

    @Nested
    class Availability {

        @Test
        void returnsEmptyWithoutContactingOllamaWhenNoEmbeddingsExistYet() {
            when(embeddingVectorRepository.hasEmbeddingsForModel(ACTIVE_MODEL)).thenReturn(false);

            assertThat(service.search("постапокалипсис", 10)).isEmpty();

            verify(ollamaEmbeddingClient, never()).embedQuery(anyString());
            verify(embeddingVectorRepository, never()).findNearestByVector(anyString(), anyInt(), anyString());
        }

        // The backfill embeds book by book. Search must work against whatever is already indexed
        // instead of waiting for full catalog coverage, otherwise every model or prompt change
        // (V165, V166) takes search down until all ~700k books are re-embedded.
        @Test
        void searchesPartialCoverageWithoutWaitingForTheBackfillToFinish() {
            mockUser(true, Set.of());
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of(candidate(1L, 0.9)));
            when(bookRepository.findAllForSummaryByIds(anyCollection()))
                    .thenReturn(List.of(book(1L, "Метро 2033")));

            assertThat(service.search("постапокалипсис", 10))
                    .extracting(result -> result.id())
                    .containsExactly(1L);

            verify(embeddingVectorRepository, never()).isSemanticActive();
        }

        // The query is embedded with the model configured right now, so it may only be compared
        // against vectors produced by that same model.
        @Test
        void searchesOnlyVectorsProducedByTheCurrentlyConfiguredModel() {
            mockUser(true, Set.of());
            when(embeddingVectorRepository.activeModel()).thenReturn("previous-model-512-v2");
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of());

            service.search("постапокалипсис", 10);

            verify(embeddingVectorRepository).findNearestByVector(anyString(), anyInt(),
                    org.mockito.ArgumentMatchers.eq(ACTIVE_MODEL));
        }

        @Test
        void returnsEmptyWhenTheEmbedderFails() {
            mockUser(true, Set.of());
            when(ollamaEmbeddingClient.embedQuery(anyString()))
                    .thenThrow(new IllegalStateException("Ollama returned an unexpected embedding dimension"));

            assertThat(service.search("постапокалипсис", 10)).isEmpty();

            verify(embeddingVectorRepository, never()).findNearestByVector(anyString(), anyInt(), anyString());
        }

        @Test
        void returnsEmptyWithoutContactingOllamaWhenUserHasNoAccessibleLibraries() {
            mockUser(false, Set.of());

            assertThat(service.search("постапокалипсис", 10)).isEmpty();

            verify(ollamaEmbeddingClient, never()).embedQuery(anyString());
        }
    }

    @Nested
    class QueryEmbedding {

        @Test
        void embedsTheQueryWithTheEmbeddingGemmaQueryPrompt() {
            mockUser(true, Set.of());
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of());

            service.search("  постапокалипсис, люди под землей  ", 10);

            verify(ollamaEmbeddingClient)
                    .embedQuery("task: search result | query: постапокалипсис, люди под землей");
        }

        @Test
        void reusesTheCachedEmbeddingForARepeatedQuery() {
            mockUser(true, Set.of());
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of());

            service.search("дюна", 10);
            service.search("дюна", 10);

            verify(ollamaEmbeddingClient, times(1)).embedQuery(anyString());
        }

        @Test
        void doesNotReuseAnEmbeddingAcrossModelVersions() {
            mockUser(true, Set.of());
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of());

            service.search("дюна", 10);
            when(ollamaEmbeddingClient.modelVersion()).thenReturn("other-model-512-v2");
            service.search("дюна", 10);

            verify(ollamaEmbeddingClient, times(2)).embedQuery(anyString());
        }
    }

    @Nested
    class Relevance {

        @Test
        void dropsCandidatesBelowTheConfiguredSimilarityThreshold() {
            mockUser(true, Set.of());
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of(candidate(1L, 0.81), candidate(2L, 0.49)));
            when(bookRepository.findAllForSummaryByIds(anyCollection()))
                    .thenReturn(List.of(book(1L, "Метро 2033")));

            assertThat(service.search("постапокалипсис", 10))
                    .extracting(result -> result.id())
                    .containsExactly(1L);
        }

        @Test
        void fallsBackToTheConfiguredDefaultWhenTheThresholdSettingIsAbsent() {
            mockUser(true, Set.of());
            mockThreshold(null);
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of(candidate(1L, 0.43), candidate(2L, 0.41)));
            when(bookRepository.findAllForSummaryByIds(anyCollection()))
                    .thenReturn(List.of(book(1L, "Метро 2033")));

            assertThat(service.search("постапокалипсис", 10))
                    .extracting(result -> result.id())
                    .containsExactly(1L);
        }

        @Test
        void preservesAnnRankOrderAndHonoursTheRequestedLimit() {
            mockUser(true, Set.of());
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of(candidate(3L, 0.9), candidate(1L, 0.8), candidate(2L, 0.7)));
            when(bookRepository.findAllForSummaryByIds(anyCollection()))
                    .thenReturn(List.of(book(1L, "One"), book(3L, "Three")));

            assertThat(service.search("постапокалипсис", 2))
                    .extracting(result -> result.id())
                    .containsExactly(3L, 1L);
        }
    }

    @Nested
    class Authorization {

        @Test
        void asksForExactlyTheRequestedLimitForAnAdmin() {
            mockUser(true, Set.of());
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of());

            service.search("постапокалипсис", 10);

            verify(embeddingVectorRepository).findNearestByVector(anyString(), org.mockito.ArgumentMatchers.eq(10),
                    org.mockito.ArgumentMatchers.eq(ACTIVE_MODEL));
            verify(bookRepository, never()).findAll(any(Specification.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void overFetchesAndFiltersCandidatesForANonAdmin() {
            mockUser(false, Set.of(9L));
            when(restrictionRepository.findByUserId(42L)).thenReturn(List.of());
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of(candidate(1L, 0.9), candidate(2L, 0.8)));
            when(bookRepository.findAll(any(Specification.class))).thenReturn(List.of(book(2L, "Allowed")));
            when(bookRepository.findAllForSummaryByIds(anyCollection()))
                    .thenReturn(List.of(book(2L, "Allowed")));

            assertThat(service.search("постапокалипсис", 10))
                    .extracting(result -> result.id())
                    .containsExactly(2L);

            verify(embeddingVectorRepository).findNearestByVector(anyString(), org.mockito.ArgumentMatchers.eq(40),
                    org.mockito.ArgumentMatchers.eq(ACTIVE_MODEL));
        }

        @SuppressWarnings("unchecked")
        @Test
        void appliesContentRestrictionsForANonAdmin() {
            mockUser(false, Set.of(9L));
            when(restrictionRepository.findByUserId(42L)).thenReturn(List.of(UserContentRestrictionEntity.builder()
                    .restrictionType(ContentRestrictionType.CATEGORY)
                    .mode(ContentRestrictionMode.EXCLUDE)
                    .value("Adult")
                    .build()));
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of(candidate(1L, 0.9), candidate(2L, 0.8)));
            when(bookRepository.findAll(any(Specification.class))).thenReturn(List.of(book(1L, "Allowed")));
            when(bookRepository.findAllForSummaryByIds(anyCollection()))
                    .thenReturn(List.of(book(1L, "Allowed")));

            assertThat(service.search("постапокалипсис", 10))
                    .extracting(result -> result.id())
                    .containsExactly(1L);
        }

        @SuppressWarnings("unchecked")
        @Test
        void doesNotHydrateWhenEveryCandidateIsFilteredOut() {
            mockUser(false, Set.of(9L));
            when(restrictionRepository.findByUserId(42L)).thenReturn(List.of());
            when(embeddingVectorRepository.findNearestByVector(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of(candidate(1L, 0.9)));
            when(bookRepository.findAll(any(Specification.class))).thenReturn(List.of());

            assertThat(service.search("постапокалипсис", 10)).isEmpty();

            verify(bookRepository, never()).findAllForSummaryByIds(anyCollection());
        }
    }

    private void mockThreshold(Double minSearchSimilarity) {
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder()
                .recommendationEmbeddingSettings(RecommendationEmbeddingSettings.builder()
                        .ollamaBaseUrl("http://ollama:11434")
                        .model("embeddinggemma:300m")
                        .dimensions(512)
                        .batchSize(64)
                        .minSearchSimilarity(minSearchSimilarity)
                        .build())
                .build());
    }

    private void mockUser(boolean admin, Set<Long> libraryIds) {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(admin);
        when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder()
                .id(42L)
                .permissions(permissions)
                .assignedLibraries(libraryIds.stream().map(id -> Library.builder().id(id).build()).toList())
                .build());
    }

    private BookEmbeddingCandidate candidate(long bookId, double score) {
        return new BookEmbeddingCandidate(bookId, score, null);
    }

    private BookEntity book(long id, String title) {
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .bookId(id)
                .title(title)
                .authors(List.of(AuthorEntity.builder().name("Дмитрий Глуховский").build()))
                .build();
        BookFileEntity file = BookFileEntity.builder()
                .fileName(title + ".epub")
                .bookType(BookFileType.EPUB)
                .build();
        return BookEntity.builder().id(id).metadata(metadata).bookFiles(List.of(file)).build();
    }
}
