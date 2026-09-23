package org.booklore.repository;

import jakarta.persistence.EntityManager;
import org.booklore.BookloreApplication;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.library.LibraryFileHelper;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Library rescans run without a surrounding transaction (they commit through bounded write
 * services), so the files returned by {@code findByLibraryId} must arrive with everything the
 * rescan reads from them already loaded. The class is deliberately not {@code @Transactional}.
 */
@SpringBootTest(classes = BookloreApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:book-additional-file-repo;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.features.oidc-enabled=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false",
        "spring.jpa.properties.hibernate.enable_lazy_load_no_trans=false"
})
@Import(BookAdditionalFileRepositoryDataJpaTest.TestConfig.class)
class BookAdditionalFileRepositoryDataJpaTest {

    @Autowired
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Autowired
    private LibraryFileHelper libraryFileHelper;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void findByLibraryId_loadsTheBookLibraryPathForUseOutsideATransaction() {
        Fixture fixture = persistBookWithFile("book.epub");

        List<BookFileEntity> files = bookAdditionalFileRepository.findByLibraryId(fixture.library().getId());

        assertThat(files).singleElement()
                .satisfies(file -> assertThat(file.getBook().getLibraryPath().getId())
                        .isEqualTo(fixture.libraryPath().getId()));
    }

    @Test
    void detectDeletedAdditionalFiles_worksOnFilesLoadedOutsideATransaction() {
        Fixture fixture = persistBookWithFile("present.epub");
        List<BookFileEntity> files = bookAdditionalFileRepository.findByLibraryId(fixture.library().getId());
        LibraryFile onDisk = LibraryFile.builder()
                .libraryEntity(fixture.library())
                .libraryPathEntity(fixture.libraryPath())
                .fileSubPath("")
                .fileName("present.epub")
                .bookFileType(BookFileType.EPUB)
                .build();

        assertThat(libraryFileHelper.detectDeletedAdditionalFiles(List.of(onDisk), files)).isEmpty();
        assertThat(libraryFileHelper.detectDeletedAdditionalFiles(List.of(), files))
                .containsExactly(files.getFirst().getId());
    }

    private Fixture persistBookWithFile(String fileName) {
        String suffix = UUID.randomUUID().toString();
        return transactionTemplate.execute(status -> {
            LibraryEntity library = LibraryEntity.builder()
                    .name("Rescan Library " + suffix)
                    .icon("book")
                    .watch(false)
                    .build();
            entityManager.persist(library);

            LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                    .library(library)
                    .path("/rescan/" + suffix)
                    .build();
            entityManager.persist(libraryPath);

            BookEntity book = BookEntity.builder()
                    .library(library)
                    .libraryPath(libraryPath)
                    .addedOn(Instant.now())
                    .build();
            entityManager.persist(book);

            entityManager.persist(BookFileEntity.builder()
                    .book(book)
                    .fileName(fileName)
                    .fileSubPath("")
                    .isBookFormat(true)
                    .bookType(BookFileType.EPUB)
                    .build());
            entityManager.flush();
            return new Fixture(library, libraryPath);
        });
    }

    private record Fixture(LibraryEntity library, LibraryPathEntity libraryPath) {
    }

    @TestConfiguration
    static class TestConfig {

        @Bean("flyway")
        @Primary
        Flyway flyway() {
            return mock(Flyway.class);
        }

        @Bean
        @Primary
        TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }
}
