package org.booklore.service.inpx;

import jakarta.persistence.EntityManager;
import org.booklore.BookloreApplication;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = BookloreApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:inpx-batch-writer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.features.oidc-enabled=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
@Import(InpxBatchWriterIntegrationTest.TestConfig.class)
class InpxBatchWriterIntegrationTest {

    @Autowired
    private InpxBatchWriter writer;
    @Autowired
    private AuthorRepository authorRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void persistsBatchWhenLaterAuthorCreationSuspendsTheBatchTransaction() {
        String suffix = UUID.randomUUID().toString();
        String existingAuthor = "Existing Author " + suffix;
        String newAuthor = "New Author " + suffix;

        long[] ids = transactionTemplate.execute(status -> {
            LibraryEntity library = LibraryEntity.builder()
                    .name("INPX Library " + suffix)
                    .icon("book")
                    .watch(false)
                    .build();
            entityManager.persist(library);

            LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                    .library(library)
                    .path("/inpx/" + suffix)
                    .build();
            entityManager.persist(libraryPath);
            entityManager.persist(AuthorEntity.builder().name(existingAuthor).build());
            entityManager.flush();
            return new long[]{library.getId(), libraryPath.getId()};
        });

        List<InpxBookDto> batch = List.of(
                book("archive.zip", "existing", existingAuthor),
                book("archive.zip", "new", newAuthor));
        InpxScanCaches caches = new InpxScanCaches();
        writer.prepareAuthors(batch, ids[0], caches);
        InpxBatchWriter.BatchResult result = writer.persist(batch, ids[0], ids[1], caches);

        assertThat(result.added()).isEqualTo(2);
        assertThat(bookRepository.countByLibraryId(ids[0])).isEqualTo(2);
        assertThat(authorRepository.findByName(newAuthor)).isPresent();
    }

    @Test
    void persistsNewBookMetadataWithAnExistingDetachedAuthor() {
        String suffix = UUID.randomUUID().toString();
        AuthorFixture fixture = transactionTemplate.execute(status -> {
            LibraryEntity library = LibraryEntity.builder()
                    .name("Detached Author Library " + suffix)
                    .icon("book")
                    .watch(false)
                    .build();
            entityManager.persist(library);

            LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                    .library(library)
                    .path("/detached-author/" + suffix)
                    .build();
            entityManager.persist(libraryPath);

            AuthorEntity author = AuthorEntity.builder()
                    .name("Detached Author " + suffix)
                    .build();
            entityManager.persist(author);
            entityManager.flush();
            return new AuthorFixture(library.getId(), libraryPath.getId(), author);
        });

        transactionTemplate.executeWithoutResult(status -> {
            BookEntity book = BookEntity.builder()
                    .library(entityManager.getReference(LibraryEntity.class, fixture.libraryId()))
                    .libraryPath(entityManager.getReference(LibraryPathEntity.class, fixture.libraryPathId()))
                    .addedOn(Instant.now())
                    .scannedOn(Instant.now())
                    .build();
            BookMetadataEntity metadata = BookMetadataEntity.builder()
                    .book(book)
                    .title("Detached Author Book")
                    .authors(new ArrayList<>(List.of(fixture.author())))
                    .build();
            book.setMetadata(metadata);

            bookRepository.saveAndFlush(book);
        });

        assertThat(bookRepository.countByLibraryId(fixture.libraryId())).isEqualTo(1);
    }

    private InpxBookDto book(String archive, String file, String author) {
        return InpxBookDto.builder()
                .id(InpxParser.id(archive, file, "fb2"))
                .archiveName(archive)
                .fileName(file)
                .extension("fb2")
                .title(file)
                .authors(List.of(author))
                .genres(List.of())
                .series("")
                .seriesNumber("")
                .libraryId("1")
                .date("")
                .language("en")
                .build();
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

    private record AuthorFixture(long libraryId, long libraryPathId, AuthorEntity author) {
    }
}
