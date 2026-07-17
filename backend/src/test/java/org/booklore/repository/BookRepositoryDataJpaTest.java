package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.springframework.boot.test.context.TestConfiguration;

@SpringBootTest(classes = {
        BookloreApplication.class
})
@Transactional
@TestPropertySource(properties = {
        "logging.level.root=debug",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false",
        "spring.jpa.properties.hibernate.enable_lazy_load_no_trans=false"
})
@Import(BookRepositoryDataJpaTest.TestConfig.class)
class BookRepositoryDataJpaTest {

    @Autowired
    private BookRepository bookRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public Flyway flyway() {
            return mock(Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    @Test
    void contextLoads() {
        assertThat(bookRepository).isNotNull();
    }

    @Test
    void findAllWithMetadataByIds_executesAgainstJpaMetamodel() {
        LibraryEntity library = LibraryEntity.builder()
                .name("Test Library")
                .icon("book")
                .watch(false)
                .formatPriority(List.of(BookFileType.EPUB, BookFileType.PDF))
                .build();
        entityManager.persist(library);
        entityManager.flush();

        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .library(library)
                .path("/test/path")
                .build();
        entityManager.persist(libraryPath);
        entityManager.flush();

        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(Instant.now())
                .deleted(false)
                .build();
        entityManager.persist(book);
        entityManager.flush();

        BookFileEntity bookFile = BookFileEntity.builder()
                .book(book)
                .fileName("test.epub")
                .fileSubPath("")
                .isBookFormat(true)
                .bookType(BookFileType.EPUB)
                .fileSizeKb(500L)
                .initialHash("hash1")
                .currentHash("hash1")
                .addedOn(Instant.now())
                .build();
        entityManager.persist(bookFile);
        entityManager.flush();

        entityManager.clear();

        Optional<BookEntity> result = bookRepository.findByIdForKoboDownload(1L);

        TestTransaction.end();

        assertThat(result).isPresent();

        BookEntity bookEntity = result.get();
        assertThat(bookEntity.getId()).isEqualTo(book.getId());
        assertThat(bookEntity.getPrimaryBookFile()).isNotNull();
    }

    /**
     * The lazy INPX cover probe's claim/release pair, exercised against a real database because
     * their defect is in the SQL itself and a mocked repository cannot see it. These are bulk JPQL
     * UPDATEs that bypass the persistence context, so each case re-reads the row afterwards.
     */
    @Nested
    class CoverProbeStatements {

        private BookEntity persistBookWithoutCover() {
            LibraryEntity library = LibraryEntity.builder()
                    .name("Probe Library")
                    .icon("book")
                    .watch(false)
                    .build();
            entityManager.persist(library);

            BookEntity book = BookEntity.builder()
                    .library(library)
                    .addedOn(Instant.now())
                    .deleted(false)
                    .build();
            entityManager.persist(book);
            entityManager.flush();
            return book;
        }

        private BookEntity reload(Long bookId) {
            entityManager.flush();
            entityManager.clear();
            return entityManager.find(BookEntity.class, bookId);
        }

        /**
         * The regression test for the Kobo re-push defect. The claim used to stamp
         * {@code metadataUpdatedAt}, which its release did not revert - so every probe that claimed
         * and then failed to write left the book looking permanently metadata-changed to
         * {@code KoboSnapshotBookRepository#findChangedBooks}, and it was re-pushed to every paired
         * device on every scan. A claim reserves; it must write nothing it cannot take back.
         */
        @Test
        void claimingACoverDoesNotTouchMetadataUpdatedAt() {
            BookEntity book = persistBookWithoutCover();
            assertThat(book.getMetadataUpdatedAt()).isNull();

            int claimed = bookRepository.markCoverFoundIfStillMissing(book.getId(), "hash-1");

            assertThat(claimed).isEqualTo(1);
            BookEntity reloaded = reload(book.getId());
            assertThat(reloaded.getBookCoverHash()).isEqualTo("hash-1");
            assertThat(reloaded.getMetadataUpdatedAt()).isNull();
        }

        /**
         * For a book with no probe marker - which is every book the lazy probe actually claims,
         * since it only claims for one it read as unmarked - claim then release is a round trip.
         * <p>
         * Note what this cannot see, and did not: it starts from {@code coverProbedAt = null}, so a
         * release that fails to restore that column passes anyway. See
         * {@link #claimFollowedByReleaseDropsAProbeMarkerThatWasThereBefore} for the case that does
         * see it. The two together are the honest statement; this one alone was read as a general
         * "claim and release are symmetric" guarantee, which is not true.
         */
        @Test
        void claimFollowedByReleaseLeavesTheRowAsItWasWhenThereWasNoMarker() {
            BookEntity book = persistBookWithoutCover();

            bookRepository.markCoverFoundIfStillMissing(book.getId(), "hash-1");
            int released = bookRepository.clearCoverHashIfStillClaimed(book.getId(), "hash-1");

            assertThat(released).isEqualTo(1);
            BookEntity reloaded = reload(book.getId());
            assertThat(reloaded.getBookCoverHash()).isNull();
            assertThat(reloaded.getMetadataUpdatedAt()).isNull();
            assertThat(reloaded.getCoverProbedAt()).isNull();
        }

        /**
         * Pins the asymmetry that {@code clearCoverHashIfStillClaimed} really has, rather than the
         * round trip its javadoc used to promise. The claim writes two columns - it sets
         * {@code bookCoverHash} <em>and</em> clears {@code coverProbedAt} - and the release reverts
         * only the first, so a marker present at claim time does not come back.
         * <p>
         * This is asserted as correct, not merely tolerated. Reaching it needs a concurrent probe to
         * have marked the book between this one's read and its claim, which means the two disagree
         * about whether the file has a cover - and this one found one, so the other's "no cover" was
         * wrong and is no loss. Leaving the book eligible for a retry after a transient save failure
         * is exactly what should happen. What must not stand is the claim that the pair is a no-op:
         * a reader deciding whether {@code coverProbedAt} survives a claim needs the real answer.
         */
        @Test
        void claimFollowedByReleaseDropsAProbeMarkerThatWasThereBefore() {
            BookEntity book = persistBookWithoutCover();
            book.setCoverProbedAt(Instant.parse("2026-01-01T00:00:00Z"));
            entityManager.flush();
            entityManager.clear();

            bookRepository.markCoverFoundIfStillMissing(book.getId(), "hash-1");
            bookRepository.clearCoverHashIfStillClaimed(book.getId(), "hash-1");

            BookEntity reloaded = reload(book.getId());
            assertThat(reloaded.getBookCoverHash()).isNull();
            assertThat(reloaded.getCoverProbedAt())
                    .as("the claim cleared the marker and the release does not restore it")
                    .isNull();
        }

        @Test
        void claimingACoverDropsAStaleProbeMarker() {
            BookEntity book = persistBookWithoutCover();
            book.setCoverProbedAt(Instant.parse("2026-01-01T00:00:00Z"));
            entityManager.flush();

            bookRepository.markCoverFoundIfStillMissing(book.getId(), "hash-1");

            assertThat(reload(book.getId()).getCoverProbedAt()).isNull();
        }

        @Test
        void claimingIsRefusedWhenTheBookAlreadyHasACover() {
            BookEntity book = persistBookWithoutCover();
            book.setBookCoverHash("someone-elses");
            entityManager.flush();

            int claimed = bookRepository.markCoverFoundIfStillMissing(book.getId(), "hash-1");

            assertThat(claimed).isZero();
            assertThat(reload(book.getId()).getBookCoverHash()).isEqualTo("someone-elses");
        }

        @Test
        void releaseOnlyClearsTheCallersOwnClaim() {
            BookEntity book = persistBookWithoutCover();
            book.setBookCoverHash("someone-elses");
            entityManager.flush();

            int released = bookRepository.clearCoverHashIfStillClaimed(book.getId(), "hash-1");

            assertThat(released).isZero();
            assertThat(reload(book.getId()).getBookCoverHash()).isEqualTo("someone-elses");
        }

        /**
         * The ordering constraint the undecodable-cover path depends on: the marker write requires
         * {@code bookCoverHash IS NULL}, so a probe that has claimed must release before it marks,
         * or the marker silently never lands and the archive is re-read forever.
         */
        @Test
        void markingProbedIsRefusedWhileACoverHashIsStillHeld() {
            BookEntity book = persistBookWithoutCover();
            bookRepository.markCoverFoundIfStillMissing(book.getId(), "hash-1");

            int marked = bookRepository.markCoverProbedIfStillMissing(book.getId(), Instant.parse("2026-02-01T00:00:00Z"));

            assertThat(marked).isZero();
            assertThat(reload(book.getId()).getCoverProbedAt()).isNull();
        }

        @Test
        void markingProbedSucceedsOnceTheClaimIsReleased() {
            BookEntity book = persistBookWithoutCover();
            Instant probedAt = Instant.parse("2026-02-01T00:00:00Z");
            bookRepository.markCoverFoundIfStillMissing(book.getId(), "hash-1");
            bookRepository.clearCoverHashIfStillClaimed(book.getId(), "hash-1");

            int marked = bookRepository.markCoverProbedIfStillMissing(book.getId(), probedAt);

            assertThat(marked).isEqualTo(1);
            BookEntity reloaded = reload(book.getId());
            assertThat(reloaded.getCoverProbedAt()).isEqualTo(probedAt);
            assertThat(reloaded.getMetadataUpdatedAt()).isNull();
        }

        /**
         * {@link BookEntity#setBookCoverHash} clears the probe marker, which would be a disaster if
         * Hibernate called it while hydrating a row: loading a book would silently rewrite its own
         * state, and a marker could never survive a round trip. It does not - {@code @Id} is on the
         * field, so Hibernate uses field access - and this pins that, since the entire marker design
         * rests on it and nothing else in the suite would notice if it changed.
         * <p>
         * The contradictory row is built with a bulk UPDATE precisely because it is the one route
         * that bypasses the setter; the invariant makes it otherwise unreachable.
         */
        @Test
        void hydratingARowDoesNotRunTheCoverHashSetterAndDropTheMarker() {
            BookEntity book = persistBookWithoutCover();
            Instant probedAt = Instant.parse("2026-03-01T00:00:00Z");
            entityManager.createQuery("UPDATE BookEntity b SET b.bookCoverHash = :hash, b.coverProbedAt = :probedAt WHERE b.id = :id")
                    .setParameter("hash", "hash-1")
                    .setParameter("probedAt", probedAt)
                    .setParameter("id", book.getId())
                    .executeUpdate();

            BookEntity reloaded = reload(book.getId());

            assertThat(reloaded.getBookCoverHash()).isEqualTo("hash-1");
            assertThat(reloaded.getCoverProbedAt()).isEqualTo(probedAt);
        }
    }
}
