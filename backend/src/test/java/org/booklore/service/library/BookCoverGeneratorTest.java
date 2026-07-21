package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.extractor.AudiobookMetadataExtractor;
import org.booklore.service.metadata.extractor.CoverExtractionException;
import org.booklore.service.metadata.extractor.FileMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookCoverGeneratorTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private FileService fileService;
    @Mock
    private MetadataExtractorFactory metadataExtractorFactory;
    @Mock
    private AudiobookMetadataExtractor audiobookMetadataExtractor;
    @Mock
    private FileMetadataExtractor epubExtractor;

    private BookCoverGenerator generator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        generator = new BookCoverGenerator(bookRepository, fileService, metadataExtractorFactory, audiobookMetadataExtractor);
    }

    private static byte[] realPngBytes() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    private LibraryPathEntity libraryPath(Path dir) {
        LibraryPathEntity path = new LibraryPathEntity();
        path.setPath(dir.toString());
        return path;
    }

    private LibraryFile fileBasedLibraryFile(Path dir, String fileName, BookFileType type) {
        return LibraryFile.builder()
                .libraryPathEntity(libraryPath(dir))
                .fileName(fileName)
                .bookFileType(type)
                .folderBased(false)
                .build();
    }

    private BookEntity bookWithoutFiles() {
        BookEntity book = new BookEntity();
        book.setId(1L);
        return book;
    }

    private BookEntity bookWithPrimaryFile(BookFileType primaryType) {
        BookEntity book = new BookEntity();
        book.setId(2L);
        BookFileEntity primary = new BookFileEntity();
        primary.setBookType(primaryType);
        book.setBookFiles(java.util.List.of(primary));
        return book;
    }

    @Nested
    @DisplayName("no existing files on the book")
    class NoExistingFiles {

        @Test
        void audiobookAdditionalFile_generatesAudiobookCover() throws IOException {
            Path audioFile = Files.createFile(tempDir.resolve("book.m4b"));
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book.m4b", BookFileType.AUDIOBOOK);
            BookEntity book = bookWithoutFiles();
            BookMetadataEntity metadata = new BookMetadataEntity();
            book.setMetadata(metadata);

            when(audiobookMetadataExtractor.extractCover(audioFile.toFile())).thenReturn(realPngBytes());
            when(fileService.saveAudiobookCoverImages(any(BufferedImage.class), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(true);

            generator.generateCoverFromAdditionalFile(book, additional);

            assertThat(book.getAudiobookCoverHash()).startsWith("BL-");
            assertThat(metadata.getAudiobookCoverUpdatedOn()).isNotNull();
            verify(bookRepository).save(book);
        }

        @Test
        void ebookAdditionalFile_generatesEbookCover() throws IOException {
            Path epubFile = Files.createFile(tempDir.resolve("book.epub"));
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book.epub", BookFileType.EPUB);
            BookEntity book = bookWithoutFiles();
            BookMetadataEntity metadata = new BookMetadataEntity();
            book.setMetadata(metadata);

            when(metadataExtractorFactory.getExtractor(BookFileType.EPUB)).thenReturn(epubExtractor);
            when(epubExtractor.extractCover(epubFile.toFile())).thenReturn(realPngBytes());
            when(fileService.saveCoverImages(any(BufferedImage.class), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(true);

            generator.generateCoverFromAdditionalFile(book, additional);

            assertThat(book.getBookCoverHash()).startsWith("BL-");
            assertThat(metadata.getCoverUpdatedOn()).isNotNull();
            verify(bookRepository).save(book);
        }

        @Test
        void missingMetadata_stillSavesBookButSkipsMetadataUpdate() throws IOException {
            Path epubFile = Files.createFile(tempDir.resolve("book.epub"));
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book.epub", BookFileType.EPUB);
            BookEntity book = bookWithoutFiles();

            when(metadataExtractorFactory.getExtractor(BookFileType.EPUB)).thenReturn(epubExtractor);
            when(epubExtractor.extractCover(epubFile.toFile())).thenReturn(realPngBytes());
            when(fileService.saveCoverImages(any(BufferedImage.class), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(true);

            generator.generateCoverFromAdditionalFile(book, additional);

            assertThat(book.getBookCoverHash()).startsWith("BL-");
            verify(bookRepository).save(book);
        }

        @Test
        void audioFileDoesNotExist_skipsWithoutSaving() {
            LibraryFile additional = fileBasedLibraryFile(tempDir, "missing.m4b", BookFileType.AUDIOBOOK);
            BookEntity book = bookWithoutFiles();

            generator.generateCoverFromAdditionalFile(book, additional);

            verify(bookRepository, never()).save(any());
        }

        @Test
        void folderBasedAudiobook_findsFirstAudioFileInFolder() throws IOException {
            Files.createFile(tempDir.resolve("00-notes.txt"));
            Path audioFile = Files.createFile(tempDir.resolve("01-chapter.m4b"));
            LibraryFile additional = LibraryFile.builder()
                    .libraryPathEntity(libraryPath(tempDir.getParent()))
                    .fileName(tempDir.getFileName().toString())
                    .bookFileType(BookFileType.AUDIOBOOK)
                    .folderBased(true)
                    .fileSubPath("")
                    .build();
            BookEntity book = bookWithoutFiles();

            when(audiobookMetadataExtractor.extractCover(audioFile.toFile())).thenReturn(realPngBytes());
            when(fileService.saveAudiobookCoverImages(any(BufferedImage.class), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(true);

            generator.generateCoverFromAdditionalFile(book, additional);

            assertThat(book.getAudiobookCoverHash()).isNotNull();
            verify(bookRepository).save(book);
        }

        @Test
        void noExtractorAvailable_skipsWithoutSaving() throws IOException {
            Files.createFile(tempDir.resolve("book.fb2"));
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book.fb2", BookFileType.FB2);
            BookEntity book = bookWithoutFiles();

            when(metadataExtractorFactory.getExtractor(BookFileType.FB2)).thenReturn(null);

            generator.generateCoverFromAdditionalFile(book, additional);

            verify(bookRepository, never()).save(any());
        }

        @Test
        void extractCoverThrows_isSwallowedAndLogged() throws IOException {
            Path epubFile = Files.createFile(tempDir.resolve("book.epub"));
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book.epub", BookFileType.EPUB);
            BookEntity book = bookWithoutFiles();

            when(metadataExtractorFactory.getExtractor(BookFileType.EPUB)).thenReturn(epubExtractor);
            when(epubExtractor.extractCover(epubFile.toFile())).thenThrow(new CoverExtractionException("corrupt epub"));

            generator.generateCoverFromAdditionalFile(book, additional);

            verify(bookRepository, never()).save(any());
        }

        @Test
        void noCoverFoundInFile_skipsWithoutSaving() throws IOException {
            Path epubFile = Files.createFile(tempDir.resolve("book.epub"));
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book.epub", BookFileType.EPUB);
            BookEntity book = bookWithoutFiles();

            when(metadataExtractorFactory.getExtractor(BookFileType.EPUB)).thenReturn(epubExtractor);
            when(epubExtractor.extractCover(epubFile.toFile())).thenReturn(null);

            generator.generateCoverFromAdditionalFile(book, additional);

            verify(bookRepository, never()).save(any());
        }

        @Test
        void undecodableCoverBytes_skipsWithoutSaving() throws IOException {
            Path epubFile = Files.createFile(tempDir.resolve("book.epub"));
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book.epub", BookFileType.EPUB);
            BookEntity book = bookWithoutFiles();

            when(metadataExtractorFactory.getExtractor(BookFileType.EPUB)).thenReturn(epubExtractor);
            when(epubExtractor.extractCover(epubFile.toFile())).thenReturn(new byte[]{1, 2, 3, 4});

            generator.generateCoverFromAdditionalFile(book, additional);

            verify(bookRepository, never()).save(any());
        }

        @Test
        void saveReturnsFalse_doesNotPersistHashOrEntity() throws IOException {
            Path epubFile = Files.createFile(tempDir.resolve("book.epub"));
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book.epub", BookFileType.EPUB);
            BookEntity book = bookWithoutFiles();

            when(metadataExtractorFactory.getExtractor(BookFileType.EPUB)).thenReturn(epubExtractor);
            when(epubExtractor.extractCover(epubFile.toFile())).thenReturn(realPngBytes());
            when(fileService.saveCoverImages(any(BufferedImage.class), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(false);

            generator.generateCoverFromAdditionalFile(book, additional);

            assertThat(book.getBookCoverHash()).isNull();
            verify(bookRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("book already has files")
    class ExistingFiles {

        @Test
        void samePrimaryAndAdditionalTypes_skipsEntirely() {
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book2.epub", BookFileType.EPUB);
            BookEntity book = bookWithPrimaryFile(BookFileType.EPUB);

            generator.generateCoverFromAdditionalFile(book, additional);

            verify(bookRepository, never()).save(any());
        }

        @Test
        void mismatchedTypes_generatesEbookCoverFromAdditionalFile() throws IOException {
            Path epubFile = Files.createFile(tempDir.resolve("book2.epub"));
            LibraryFile additional = fileBasedLibraryFile(tempDir, "book2.epub", BookFileType.EPUB);
            BookEntity book = bookWithPrimaryFile(BookFileType.AUDIOBOOK);
            BookMetadataEntity metadata = new BookMetadataEntity();
            book.setMetadata(metadata);

            when(metadataExtractorFactory.getExtractor(BookFileType.EPUB)).thenReturn(epubExtractor);
            when(epubExtractor.extractCover(epubFile.toFile())).thenReturn(realPngBytes());
            when(fileService.saveCoverImages(any(BufferedImage.class), org.mockito.ArgumentMatchers.eq(2L))).thenReturn(true);

            generator.generateCoverFromAdditionalFile(book, additional);

            assertThat(book.getBookCoverHash()).startsWith("BL-");
            verify(bookRepository).save(book);
        }
    }
}
