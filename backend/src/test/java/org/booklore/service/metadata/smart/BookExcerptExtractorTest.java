package org.booklore.service.metadata.smart;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookExcerptExtractorTest {

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final ArchivedBookContentService archivedBookContentService = mock(ArchivedBookContentService.class);
    private final BookExcerptExtractor extractor =
            new BookExcerptExtractor(new Fb2MetadataExtractor(), bookRepository, archivedBookContentService);

    @TempDir
    Path tempDir;

    private Path writeFb2(String name) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook>
                  <body><p>Рассвет рыцаря</p><p>Сергей Хантер</p></body>
                </FictionBook>
                """;
        Path file = tempDir.resolve(name);
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file;
    }

    private Book fb2BookAt(String path) {
        return Book.builder()
                .id(1L)
                .primaryFile(BookFile.builder().bookType(BookFileType.FB2).filePath(path).build())
                .build();
    }

    @Test
    void readsTheOpeningTextOfAnFb2File() throws IOException {
        Path file = writeFb2("book.fb2");

        assertThat(extractor.openingText(fb2BookAt(file.toString()), 2500).orElseThrow())
                .contains("Рассвет рыцаря")
                .contains("Сергей Хантер");
    }

    @Test
    void returnsEmptyForANonFb2File() throws IOException {
        Path file = writeFb2("book.fb2");
        Book epub = Book.builder()
                .id(2L)
                .primaryFile(BookFile.builder().bookType(BookFileType.EPUB).filePath(file.toString()).build())
                .build();

        assertThat(extractor.openingText(epub, 2500)).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheFileIsNotOnDisk() {
        assertThat(extractor.openingText(fb2BookAt(tempDir.resolve("missing.fb2").toString()), 2500)).isEmpty();
    }

    @Test
    void returnsEmptyWhenThereIsNoPrimaryFile() {
        assertThat(extractor.openingText(Book.builder().id(3L).build(), 2500)).isEmpty();
    }

    @Test
    void readsOpeningTextOfAnArchivedFb2ByResolvingTheArchive() throws IOException {
        // The DTO path of an archived book points at a library-relative file that does not exist on
        // disk — the real FB2 bytes live inside the ZIP and are only reachable through the extraction
        // cache. The extractor must resolve the archive, not read the bogus path.
        Path resolved = writeFb2("resolved.fb2");
        Book archived = Book.builder()
                .id(9L)
                .primaryFile(BookFile.builder()
                        .bookType(BookFileType.FB2)
                        .sourceArchive("books.zip")
                        .filePath(tempDir.resolve("does-not-exist.fb2").toString())
                        .build())
                .build();

        BookFileEntity primary = mock(BookFileEntity.class);
        when(primary.isArchivedSource()).thenReturn(true);
        BookEntity entity = mock(BookEntity.class);
        when(entity.getPrimaryBookFile()).thenReturn(primary);
        when(bookRepository.findByIdWithBookFiles(9L)).thenReturn(Optional.of(entity));
        when(archivedBookContentService.resolve(primary)).thenReturn(resolved);

        assertThat(extractor.openingText(archived, 2500).orElseThrow())
                .contains("Рассвет рыцаря")
                .contains("Сергей Хантер");
    }
}
