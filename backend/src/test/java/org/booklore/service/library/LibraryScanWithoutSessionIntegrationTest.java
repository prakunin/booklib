package org.booklore.service.library;

import jakarta.persistence.EntityManager;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.task.TaskCronService;
import org.booklore.task.options.RescanLibraryContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Library scans deliberately run without a surrounding transaction: they execute on the task
 * executor and commit through bounded write services. Every entity they read must therefore arrive
 * fully loaded. These tests run whole scans against a real database and a real directory, with no
 * session open, so a lazy association anywhere on the path fails the test the way it fails in
 * production. The class is deliberately not {@code @Transactional}.
 */
@SpringBootTest(classes = BookloreApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:library-scan-without-session;DB_CLOSE_DELAY=-1",
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
@Import(LibraryScanWithoutSessionIntegrationTest.TestConfig.class)
class LibraryScanWithoutSessionIntegrationTest {

    @Autowired
    private LibraryProcessingService libraryProcessingService;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    // New books are not what these tests are about; keep file parsing and cover rendering out.
    @MockitoBean
    private FileAsBookProcessor fileAsBookProcessor;
    @MockitoBean
    private BookCoverGenerator bookCoverGenerator;

    @TempDir
    private Path root;

    @Test
    void rescan_removesTheRecordOfAnAdditionalFileThatIsGoneFromDisk() throws IOException {
        Path folder = Files.createDirectories(root.resolve("Author/Book"));
        Files.writeString(folder.resolve("book.epub"), "epub");
        Fixture fixture = persistLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER);
        BookEntity book = persistBook(fixture, "Book");
        persistFile(book, "Author/Book", "book.epub", BookFileType.EPUB);
        Long gone = persistFile(book, "Author/Book", "book.pdf", BookFileType.PDF);

        rescan(fixture);

        assertThat(bookAdditionalFileRepository.findById(gone)).isEmpty();
        assertThat(bookRepository.findById(book.getId())).get()
                .extracting(BookEntity::getDeleted)
                .isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void rescan_attachesANewFileToTheBookAlreadyInItsFolder() throws IOException {
        Path folder = Files.createDirectories(root.resolve("Author/Book"));
        Files.writeString(folder.resolve("book.epub"), "epub");
        Files.writeString(folder.resolve("book.pdf"), "pdf");
        Fixture fixture = persistLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER);
        BookEntity book = persistBook(fixture, "Book");
        persistFile(book, "Author/Book", "book.epub", BookFileType.EPUB);

        rescan(fixture);

        assertThat(fileNamesOf(book)).containsExactlyInAnyOrder("book.epub", "book.pdf");
    }

    @Test
    void rescan_attachesANewFileToAFilelessBookWithTheSameTitle() throws IOException {
        Files.writeString(root.resolve("Dune.epub"), "epub");
        Fixture fixture = persistLibrary(LibraryOrganizationMode.BOOK_PER_FILE);
        BookEntity fileless = persistBook(fixture, "Dune");

        rescan(fixture);

        assertThat(fileNamesOf(fileless)).containsExactly("Dune.epub");
    }

    @Test
    void initialScan_skipsFilesThatAlreadyBelongToABook() throws IOException {
        Path folder = Files.createDirectories(root.resolve("Author/Book"));
        Files.writeString(folder.resolve("book.epub"), "epub");
        Fixture fixture = persistLibrary(LibraryOrganizationMode.BOOK_PER_FOLDER);
        BookEntity book = persistBook(fixture, "Book");
        persistFile(book, "Author/Book", "book.epub", BookFileType.EPUB);

        assertThatCode(() -> libraryProcessingService.processLibrary(fixture.libraryId()))
                .doesNotThrowAnyException();
        verify(fileAsBookProcessor).processLibraryFilesGrouped(argThat(Map::isEmpty), any());
    }

    private void rescan(Fixture fixture) throws IOException {
        libraryProcessingService.rescanLibrary(RescanLibraryContext.builder().libraryId(fixture.libraryId()).build());
    }

    private List<String> fileNamesOf(BookEntity book) {
        return bookRepository.findByIdWithBookFiles(book.getId()).orElseThrow()
                .getBookFiles().stream()
                .map(BookFileEntity::getFileName)
                .toList();
    }

    private Fixture persistLibrary(LibraryOrganizationMode mode) {
        String suffix = UUID.randomUUID().toString();
        return transactionTemplate.execute(status -> {
            LibraryEntity library = LibraryEntity.builder()
                    .name("Scan Library " + suffix)
                    .icon("book")
                    .watch(false)
                    .organizationMode(mode)
                    .formatPriority(List.of(BookFileType.EPUB, BookFileType.PDF))
                    .build();
            entityManager.persist(library);
            LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                    .library(library)
                    .path(root.toString())
                    .build();
            entityManager.persist(libraryPath);
            return new Fixture(library.getId(), libraryPath.getId());
        });
    }

    private BookEntity persistBook(Fixture fixture, String title) {
        return transactionTemplate.execute(status -> {
            BookEntity book = BookEntity.builder()
                    .library(entityManager.getReference(LibraryEntity.class, fixture.libraryId()))
                    .libraryPath(entityManager.getReference(LibraryPathEntity.class, fixture.libraryPathId()))
                    .addedOn(Instant.now())
                    .build();
            book.setMetadata(BookMetadataEntity.builder().book(book).title(title).build());
            entityManager.persist(book);
            return book;
        });
    }

    private Long persistFile(BookEntity book, String subPath, String fileName, BookFileType type) {
        return transactionTemplate.execute(status -> {
            BookFileEntity file = BookFileEntity.builder()
                    .book(entityManager.getReference(BookEntity.class, book.getId()))
                    .fileName(fileName)
                    .fileSubPath(subPath)
                    .isBookFormat(true)
                    .bookType(type)
                    .addedOn(Instant.now())
                    .build();
            entityManager.persist(file);
            return file.getId();
        });
    }

    private record Fixture(Long libraryId, Long libraryPathId) {
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
