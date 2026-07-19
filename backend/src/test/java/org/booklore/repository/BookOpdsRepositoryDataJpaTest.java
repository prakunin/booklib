package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.OpdsSortOrder;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.springframework.boot.test.context.TestConfiguration;



@SpringBootTest(classes = {
        BookloreApplication.class
})
@Transactional
@TestPropertySource(properties = {
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
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
@Import(BookOpdsRepositoryDataJpaTest.TestConfig.class)
class BookOpdsRepositoryDataJpaTest {

    @Autowired
    private BookOpdsRepository bookOpdsRepository;

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
        assertThat(bookOpdsRepository).isNotNull();
    }

    @Test
    void findAllWithMetadataByIds_executesAgainstJpaMetamodel() {
        LibraryEntity library = LibraryEntity.builder()
                .name("Test Library")
                .icon("book")
                .watch(false)
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

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book)
                .bookId(book.getId())
                .title("Test Title")
                .build();
        entityManager.persist(metadata);
        entityManager.flush();

        List<BookEntity> result = bookOpdsRepository.findAllWithMetadataByIds(List.of(book.getId()));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(book.getId());
    }

    @Test
    void findBookIds_sortsBeforePagination() {
        LibraryEntity library = persistLibrary("Sorted Library");
        LibraryPathEntity libraryPath = persistLibraryPath(library);

        persistBook(library, libraryPath, "Zulu", Instant.parse("2026-01-03T00:00:00Z"));
        Long alphaId = persistBook(library, libraryPath, "Alpha", Instant.parse("2026-01-01T00:00:00Z"));
        Long betaId = persistBook(library, libraryPath, "Beta", Instant.parse("2026-01-02T00:00:00Z"));
        entityManager.flush();
        entityManager.clear();

        Page<Long> firstPage = bookOpdsRepository.findBookIds(OpdsSortOrder.TITLE_ASC, PageRequest.of(0, 1));
        Page<Long> secondPage = bookOpdsRepository.findBookIds(OpdsSortOrder.TITLE_ASC, PageRequest.of(1, 1));

        assertThat(firstPage.getContent()).containsExactly(alphaId);
        assertThat(secondPage.getContent()).containsExactly(betaId);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        for (OpdsSortOrder sortOrder : OpdsSortOrder.values()) {
            assertThat(bookOpdsRepository.findBookIds(sortOrder, PageRequest.of(0, 2)).getTotalElements()).isEqualTo(3);
        }
    }

    private LibraryEntity persistLibrary(String name) {
        LibraryEntity library = LibraryEntity.builder()
                .name(name)
                .icon("book")
                .watch(false)
                .build();
        entityManager.persist(library);
        return library;
    }

    private LibraryPathEntity persistLibraryPath(LibraryEntity library) {
        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .library(library)
                .path("/test/" + library.getName())
                .build();
        entityManager.persist(libraryPath);
        return libraryPath;
    }

    private Long persistBook(LibraryEntity library, LibraryPathEntity libraryPath, String title, Instant addedOn) {
        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(addedOn)
                .deleted(false)
                .build();
        entityManager.persist(book);
        entityManager.flush();

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book)
                .bookId(book.getId())
                .title(title)
                .build();
        entityManager.persist(metadata);
        return book.getId();
    }
}
