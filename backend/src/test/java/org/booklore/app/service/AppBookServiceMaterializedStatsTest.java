package org.booklore.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.app.dto.AppLibraryStats;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.repository.BookRepository;
import org.booklore.service.browse.BookSpecifications;
import org.springframework.data.jpa.domain.Specification;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Parity check for the materialized library statistics: {@link AppBookService#getLibraryStats} must
 * return the same user-independent aggregates whether they are computed live or read from the tables
 * the recompute populates. Runs on H2 with an admin (unrestricted, so eligible for the materialized
 * path).
 */
@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:matstats;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,YEAR,MONTH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(AppBookServiceMaterializedStatsTest.TestConfig.class)
class AppBookServiceMaterializedStatsTest {

    @Autowired
    private AppBookService appBookService;

    @Autowired
    private BookRepository bookRepository;

    @MockitoBean
    private AuthenticationService authenticationService;

    @PersistenceContext
    private EntityManager em;

    private LibraryEntity library;
    private LibraryPathEntity libraryPath;
    private Long userId;

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

    @BeforeEach
    void seed() {
        BookLoreUserEntity userEntity = BookLoreUserEntity.builder()
                .username("admin").passwordHash("x").name("Admin").build();
        em.persist(userEntity);

        var permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        userId = userEntity.getId();
        BookLoreUser admin = BookLoreUser.builder().id(userEntity.getId()).permissions(permissions).build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(admin);

        library = LibraryEntity.builder().name("Lib").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(library);
        libraryPath = LibraryPathEntity.builder().library(library).path("/p").build();
        em.persist(libraryPath);

        AuthorEntity ann = author("Ann");
        AuthorEntity bob = author("Bob");
        AuthorEntity cara = author("Cara");

        BookEntity a = book(BookFileType.EPUB, 5_000L);
        meta(a, "A", "Foundation", "Penguin", 2020, 300, List.of(ann, bob));

        BookEntity b = book(BookFileType.PDF, 20_000L);
        meta(b, "B", "Foundation", "Penguin", 2021, 150, List.of(bob, cara));

        BookEntity c = book(BookFileType.EPUB, 100_000L);
        meta(c, "C", null, "Acme", 1999, 800, List.of(ann));

        em.flush();
        em.clear();
    }

    // Compares each materialized read against the live aggregate the recompute is built from, per
    // library. getLibraryStats itself is not exercised here: its averageDaysToFinish uses DATEDIFF,
    // which the H2 test dialect rejects (it works on the production MariaDB), and that live aggregate
    // is out of scope for materialization.
    @Test
    void materializedStatsMatchLiveComputationForSingleLibrary() {
        Long libraryId = library.getId();
        Specification<BookEntity> spec = BookSpecifications.notDeleted()
                .and(BookSpecifications.inLibrary(libraryId));

        long liveBooks = bookRepository.count(spec);
        long liveSize = appBookService.sumBookFileSize(spec);
        long liveAuthors = appBookService.countDistinctAuthors(spec, true);
        long liveSeries = appBookService.countDistinctSeries(spec);
        long livePublishers = appBookService.countDistinctPublishers(spec);
        List<AppLibraryStats.MonthlyCount> liveMonths =
                appBookService.countBooksByMonth(spec, "addedOn", userId, false);
        List<AppLibraryStats.AuthorStat> liveAuthorStats = appBookService.aggregateAuthors(spec, userId);

        appBookService.recomputeLibraryStats(libraryId);
        appBookService.recomputeCatalogStats();

        // Sanity: the seed produced non-trivial aggregates to compare against.
        assertThat(liveBooks).isEqualTo(3);
        assertThat(liveAuthorStats).isNotEmpty();

        List<Long> scope = List.of(libraryId);
        Map<String, Long> sums = appBookService.materializedScalarSums(scope);
        assertThat(sums)
                .containsEntry("TOTAL_BOOKS", liveBooks)
                .containsEntry("TOTAL_SIZE_KB", liveSize)
                .containsEntry("TOTAL_AUTHORS", liveAuthors)
                .containsEntry("TOTAL_SERIES", liveSeries)
                .containsEntry("TOTAL_PUBLISHERS", livePublishers);

        assertThat(appBookService.materializedMonths(scope)).isEqualTo(liveMonths);
        assertThat(appBookService.materializedAuthorStats(false, scope, spec, userId))
                .isEqualTo(liveAuthorStats);

        // The whole-catalog scope (one library here) must match the same live aggregates.
        Map<String, Long> catalog = appBookService.catalogScalarMap();
        assertThat(catalog)
                .containsEntry("TOTAL_AUTHORS", liveAuthors)
                .containsEntry("TOTAL_SERIES", liveSeries)
                .containsEntry("TOTAL_PUBLISHERS", livePublishers);
        assertThat(appBookService.materializedAuthorStats(true, List.of(), spec, userId))
                .isEqualTo(liveAuthorStats);
    }

    @Test
    void unmaterializedScopeFallsBackToLiveInsteadOfServingZeros() {
        Long libraryId = library.getId();
        // No recompute has run, so no stat rows exist. The scope must not be treated as materialized;
        // otherwise the read path would serve empty rows as zero counts.
        AppBookService.StatScope scope = appBookService.resolveStatScope(true, null, libraryId);
        assertThat(scope.additiveMaterialized()).isFalse();
        assertThat(scope.distinctMaterialized()).isFalse();

        // After recompute, the same scope is eligible for materialized reads.
        appBookService.recomputeLibraryStats(libraryId);
        AppBookService.StatScope afterRecompute = appBookService.resolveStatScope(true, null, libraryId);
        assertThat(afterRecompute.additiveMaterialized()).isTrue();
        assertThat(afterRecompute.distinctMaterialized()).isTrue();
    }

    @Test
    void contentRestrictedUserIsNeverServedMaterializedStats() {
        // simpleVisibility == false marks a content-restricted user; every scope must stay live.
        AppBookService.StatScope scope = appBookService.resolveStatScope(false, null, library.getId());
        assertThat(scope.additiveMaterialized()).isFalse();
        assertThat(scope.distinctMaterialized()).isFalse();
    }

    private BookEntity book(BookFileType fileType, long fileSizeKb) {
        BookEntity book = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now())
                .hasFiles(true).isPhysical(false).deleted(false).build();
        em.persist(book);

        BookFileEntity file = BookFileEntity.builder()
                .book(book).fileName("f").fileSubPath("p").isBookFormat(true).bookType(fileType)
                .fileSizeKb(fileSizeKb).addedOn(Instant.now()).build();
        em.persist(file);
        return book;
    }

    private void meta(BookEntity book, String title, String seriesName, String publisher,
                      int publishedYear, Integer pageCount, List<AuthorEntity> authors) {
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book).title(title).seriesName(seriesName).publisher(publisher)
                .publishedDate(LocalDate.of(publishedYear, Month.JANUARY, 1)).pageCount(pageCount).build();
        metadata.setAuthors(new ArrayList<>(authors));
        em.persist(metadata);
        book.setMetadata(metadata);
    }

    private AuthorEntity author(String name) {
        AuthorEntity author = AuthorEntity.builder().name(name).build();
        em.persist(author);
        return author;
    }
}
