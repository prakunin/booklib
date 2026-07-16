package org.booklore.service.recommender;

import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BookVectorServiceTest {

    private final BookVectorService service = new BookVectorService(new ObjectMapper());

    @Nested
    class TransactionBoundaries {

        // These methods operate purely on in-memory arrays / already-initialized entities and must
        // never open a JPA transaction: cosineSimilarity is invoked in an N-by-N inner loop, so a
        // transactional proxy would open one transaction per pairwise comparison, and every call
        // would fail with CannotCreateTransactionException whenever the EntityManagerFactory is
        // unavailable (e.g. a background task outliving a context restart).
        @Test
        void classIsNotTransactional() {
            assertThat(BookVectorService.class.isAnnotationPresent(Transactional.class))
                    .as("BookVectorService performs no DB access and must not be @Transactional")
                    .isFalse();
        }

        @Test
        void noDeclaredMethodIsTransactional() {
            for (Method method : BookVectorService.class.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(Transactional.class))
                        .as("%s must not be @Transactional", method.getName())
                        .isFalse();
            }
        }
    }

    @Nested
    class CosineSimilarity {

        @Test
        void returnsDotProductForNormalizedVectors() {
            double[] v = {1.0, 0.0, 0.0};
            assertThat(service.cosineSimilarity(v, v)).isCloseTo(1.0, within(1e-9));
        }

        @Test
        void returnsZeroForOrthogonalVectors() {
            assertThat(service.cosineSimilarity(new double[]{1.0, 0.0}, new double[]{0.0, 1.0}))
                    .isCloseTo(0.0, within(1e-9));
        }

        @Test
        void returnsZeroWhenAnyVectorIsNullOrMismatchedLength() {
            assertThat(service.cosineSimilarity(null, new double[]{1.0})).isZero();
            assertThat(service.cosineSimilarity(new double[]{1.0}, null)).isZero();
            assertThat(service.cosineSimilarity(new double[]{1.0}, new double[]{1.0, 2.0})).isZero();
        }
    }

    @Nested
    class Embedding {

        @Test
        void generatesNormalizedEmbeddingFromMetadata() {
            AuthorEntity author = AuthorEntity.builder().name("Ursula K. Le Guin").build();
            BookMetadataEntity metadata = BookMetadataEntity.builder()
                    .title("A Wizard of Earthsea")
                    .seriesName("Earthsea")
                    .authors(List.of(author))
                    .build();
            BookEntity book = BookEntity.builder().id(1L).metadata(metadata).build();

            double[] embedding = service.generateEmbedding(book);

            double norm = 0.0;
            for (double component : embedding) {
                norm += component * component;
            }
            assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-9));
        }

        @Test
        void returnsZeroVectorWhenMetadataMissing() {
            BookEntity book = BookEntity.builder().id(1L).build();
            assertThat(service.generateEmbedding(book)).containsOnly(0.0);
        }
    }

    @Nested
    class Serialization {

        @Test
        void roundTripsVector() {
            double[] vector = {0.1, 0.2, 0.3, -0.4};
            String json = service.serializeVector(vector);
            assertThat(service.deserializeVector(json)).containsExactly(vector);
        }

        @Test
        void deserializeReturnsNullForBlankInput() {
            assertThat(service.deserializeVector(null)).isNull();
            assertThat(service.deserializeVector("")).isNull();
        }
    }
}
