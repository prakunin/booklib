package org.booklore.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.browse.BookSpecifications;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Characterizes {@link AppBookService#countDistinctAuthors(Specification, boolean)}: the author-side
 * fast path (simpleVisibility = true) must return the same count as the book-side query it replaces
 * (simpleVisibility = false), and both must equal the true number of distinct authors with at least
 * one visible book. Runs on H2, so it proves query equivalence, not the MariaDB speedup.
 */
@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:countauthorstest;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
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
@Import(AppBookServiceCountDistinctAuthorsTest.TestConfig.class)
class AppBookServiceCountDistinctAuthorsTest {

    @Autowired
    private AppBookService appBookService;

    @PersistenceContext
    private EntityManager em;

    private LibraryEntity library;
    private LibraryPathEntity libraryPath;
    private final Map<String, AuthorEntity> authorsByName = new HashMap<>();

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
        library = LibraryEntity.builder().name("Lib").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(library);
        libraryPath = LibraryPathEntity.builder().library(library).path("/p").build();
        em.persist(libraryPath);

        // Visible via has_files, authors {Ann, Bob}.
        digital(false, "Ann", "Bob");
        // Visible via is_physical only, authors {Bob, Cara} - Bob shared, so it must be deduped.
        physical("Bob", "Cara");
        // Visible with no authors - must not affect the count.
        digital(false);
        // Deleted: its author Dan must be excluded.
        digital(true, "Dan");
        // Shell (no files, not physical): its author Eve must be excluded.
        shell("Eve");

        em.flush();
        em.clear();
    }

    @Test
    void authorSideFastPathMatchesBookSideCount() {
        Specification<BookEntity> visible = BookSpecifications.notDeleted()
                .and(BookSpecifications.hasDigitalFileOrIsPhysical());

        long fast = appBookService.countDistinctAuthors(visible, true);
        long bookSide = appBookService.countDistinctAuthors(visible, false);

        // Distinct authors with a visible book: Ann, Bob, Cara. Dan (deleted) and Eve (shell) excluded.
        assertThat(fast).isEqualTo(3);
        assertThat(bookSide).isEqualTo(3);
        assertThat(fast).isEqualTo(bookSide);
    }

    private void digital(boolean deleted, String... authors) {
        book(true, false, deleted, authors);
    }

    private void physical(String... authors) {
        book(false, true, false, authors);
    }

    private void shell(String... authors) {
        book(false, false, false, authors);
    }

    private void book(boolean hasFiles, boolean physical, boolean deleted, String... authorNames) {
        BookEntity book = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now())
                .hasFiles(hasFiles).isPhysical(physical).deleted(deleted).build();
        em.persist(book);

        BookMetadataEntity metadata = BookMetadataEntity.builder().book(book).title("t").build();
        List<AuthorEntity> authors = new ArrayList<>();
        for (String name : authorNames) {
            authors.add(author(name));
        }
        metadata.setAuthors(authors);
        em.persist(metadata);
        book.setMetadata(metadata);
    }

    private AuthorEntity author(String name) {
        return authorsByName.computeIfAbsent(name, n -> {
            AuthorEntity author = AuthorEntity.builder().name(n).build();
            em.persist(author);
            return author;
        });
    }
}
