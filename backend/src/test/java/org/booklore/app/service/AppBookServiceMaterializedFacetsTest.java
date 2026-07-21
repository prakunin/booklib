package org.booklore.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.app.dto.AppFilterOptions;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.entity.TagEntity;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Parity check for the materialized facet counts: {@link AppBookService#getFilterOptions} must return
 * the same facets whether it computes them live or reads them from the table the recompute task
 * populates. Runs on H2 with an admin (unrestricted, so eligible for the materialized path).
 */
@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:matfacets;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
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
@Import(AppBookServiceMaterializedFacetsTest.TestConfig.class)
class AppBookServiceMaterializedFacetsTest {

    @Autowired
    private AppBookService appBookService;

    @Autowired
    private FilterOptionsCache filterOptionsCache;

    @MockitoBean
    private AuthenticationService authenticationService;

    @PersistenceContext
    private EntityManager em;

    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

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
        BookLoreUser admin = BookLoreUser.builder().id(userEntity.getId()).permissions(permissions).build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(admin);

        library = LibraryEntity.builder().name("Lib").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(library);
        libraryPath = LibraryPathEntity.builder().library(library).path("/p").build();
        em.persist(libraryPath);

        ShelfEntity shelf = ShelfEntity.builder().name("Favorites").isPublic(false).user(userEntity).build();
        em.persist(shelf);

        AuthorEntity ann = author("Ann");
        AuthorEntity bob = author("Bob");
        AuthorEntity cara = author("Cara");
        CategoryEntity fiction = category("Fiction");
        CategoryEntity drama = category("Drama");
        TagEntity scifi = tag("scifi");
        TagEntity classic = tag("classic");

        BookEntity a = book(0.96f, BookFileType.EPUB, 5_000L, shelf);
        meta(a, "A", "en", "Foundation", "Penguin", "Narrator One", 2020, 16, "Mature", 300, 4.6,
                List.of(ann, bob), List.of(fiction), List.of(scifi));

        BookEntity b = book(0.85f, BookFileType.PDF, 20_000L, null);
        meta(b, "B", "fr", "Foundation", "Penguin", "Narrator Two", 2021, 12, "Everyone", 150, 3.2,
                List.of(bob, cara), List.of(fiction, drama), List.of(classic));

        BookEntity c = book(0.40f, BookFileType.EPUB, 100_000L, null);
        meta(c, "C", "en", null, "Acme", null, 1999, null, null, 800, null,
                List.of(ann), List.of(), List.of());

        em.flush();
        em.clear();
    }

    @Test
    void materializedFacetsMatchLiveComputation() {
        AppFilterOptions live = appBookService.getFilterOptions(library.getId(), null, null);

        filterOptionsCache.invalidateAll();
        appBookService.recomputeLibraryFacetCounts(library.getId());
        filterOptionsCache.invalidateAll();

        AppFilterOptions materialized = appBookService.getFilterOptions(library.getId(), null, null);

        // Sanity: the live path actually produced non-trivial facets to compare against.
        assertThat(live.authors()).isNotEmpty();
        assertThat(live.series()).isNotEmpty();
        assertThat(live.fileTypes()).isNotEmpty();

        assertOptionsEqual(live.authors(), materialized.authors());
        assertLanguagesEqual(live.languages(), materialized.languages());
        assertOptionsEqual(live.fileTypes(), materialized.fileTypes());
        assertOptionsEqual(live.categories(), materialized.categories());
        assertOptionsEqual(live.publishers(), materialized.publishers());
        assertOptionsEqual(live.series(), materialized.series());
        assertOptionsEqual(live.tags(), materialized.tags());
        assertOptionsEqual(live.moods(), materialized.moods());
        assertOptionsEqual(live.narrators(), materialized.narrators());
        assertOptionsEqual(live.ageRatings(), materialized.ageRatings());
        assertOptionsEqual(live.contentRatings(), materialized.contentRatings());
        assertOptionsEqual(live.matchScores(), materialized.matchScores());
        assertOptionsEqual(live.publishedYears(), materialized.publishedYears());
        assertOptionsEqual(live.fileSizes(), materialized.fileSizes());
        assertOptionsEqual(live.amazonRatings(), materialized.amazonRatings());
        assertOptionsEqual(live.goodreadsRatings(), materialized.goodreadsRatings());
        assertOptionsEqual(live.pageCounts(), materialized.pageCounts());
        assertOptionsEqual(live.shelfStatuses(), materialized.shelfStatuses());
        assertOptionsEqual(live.comicCharacters(), materialized.comicCharacters());
        assertOptionsEqual(live.comicCreators(), materialized.comicCreators());
        assertOptionsEqual(live.shelves(), materialized.shelves());
        assertOptionsEqual(live.libraries(), materialized.libraries());
        // User-scoped facets are computed live in both paths and must still match.
        assertOptionsEqual(live.readStatuses(), materialized.readStatuses());
        assertOptionsEqual(live.personalRatings(), materialized.personalRatings());
    }

    private void assertOptionsEqual(List<AppFilterOptions.CountedOption> live,
                                    List<AppFilterOptions.CountedOption> materialized) {
        assertThat(norm(materialized)).isEqualTo(norm(live));
    }

    private void assertLanguagesEqual(List<AppFilterOptions.LanguageOption> live,
                                      List<AppFilterOptions.LanguageOption> materialized) {
        Comparator<AppFilterOptions.LanguageOption> byCountThenCode =
                Comparator.comparingLong(AppFilterOptions.LanguageOption::count).reversed()
                        .thenComparing(AppFilterOptions.LanguageOption::code);
        assertThat(materialized.stream().sorted(byCountThenCode).toList())
                .isEqualTo(live.stream().sorted(byCountThenCode).toList());
    }

    private static List<AppFilterOptions.CountedOption> norm(List<AppFilterOptions.CountedOption> options) {
        return options.stream()
                .sorted(Comparator.comparingLong(AppFilterOptions.CountedOption::count).reversed()
                        .thenComparing(o -> o.name() == null ? "" : o.name()))
                .toList();
    }

    private BookEntity book(float matchScore, BookFileType fileType, long fileSizeKb, ShelfEntity shelf) {
        BookEntity book = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now())
                .hasFiles(true).isPhysical(false).deleted(false).metadataMatchScore(matchScore).build();
        if (shelf != null) {
            book.setShelves(new HashSet<>(List.of(shelf)));
        }
        em.persist(book);

        BookFileEntity file = BookFileEntity.builder()
                .book(book).fileName("f").fileSubPath("p").isBookFormat(true).bookType(fileType)
                .fileSizeKb(fileSizeKb).addedOn(Instant.now()).build();
        em.persist(file);
        return book;
    }

    private void meta(BookEntity book, String title, String language, String seriesName, String publisher,
                      String narrator, int publishedYear, Integer ageRating, String contentRating,
                      Integer pageCount, Double amazonRating, List<AuthorEntity> authors,
                      List<CategoryEntity> categories, List<TagEntity> tags) {
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book).title(title).language(language).seriesName(seriesName).publisher(publisher)
                .narrator(narrator).publishedDate(LocalDate.of(publishedYear, 1, 1)).ageRating(ageRating)
                .contentRating(contentRating).pageCount(pageCount).amazonRating(amazonRating).build();
        metadata.setAuthors(new ArrayList<>(authors));
        metadata.setCategories(new HashSet<>(categories));
        metadata.setTags(new HashSet<>(tags));
        em.persist(metadata);
        book.setMetadata(metadata);
    }

    private AuthorEntity author(String name) {
        AuthorEntity author = AuthorEntity.builder().name(name).build();
        em.persist(author);
        return author;
    }

    private CategoryEntity category(String name) {
        CategoryEntity category = CategoryEntity.builder().name(name).build();
        em.persist(category);
        return category;
    }

    private TagEntity tag(String name) {
        TagEntity tag = TagEntity.builder().name(name).build();
        em.persist(tag);
        return tag;
    }
}
