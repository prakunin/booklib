package org.booklore.service.book;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.*;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.kobo.CbxConversionService;
import org.booklore.service.kobo.KepubConversionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookDownloadServiceTest {

    @Mock private BookRepository bookRepository;

    @Mock private BookFileRepository bookFileRepository;

    @Mock private KepubConversionService kepubConversionService;

    @Mock private CbxConversionService cbxConversionService;

    @Mock private AppSettingService appSettingService;

    @InjectMocks
    private BookDownloadService bookDownloadService;

    private MockedStatic<Files> mockFiles;

    @BeforeEach
    void setup() {
        mockFiles = mockStatic(Files.class);
    }

    @AfterEach
    void tearDown() {
        mockFiles.close();
    }

    @Test
    void downloadBook_includesContentDispositionAscii() {
        String expected = "attachment; filename=\"example.epub\"";

        BookEntity bookEntity = getSampleBook("example.epub");
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));

        mockFiles.when(() -> Files.exists(bookEntity.getFullFilePath())).thenReturn(true);
        mockFiles.when(() -> Files.isDirectory(bookEntity.getFullFilePath())).thenReturn(false);

        String actual = bookDownloadService.downloadBook(1L)
                .getHeaders()
                .getFirst("Content-Disposition");

        assertEquals(expected, actual);
    }

    @Test
    void contentDispositionIncludesUtf8FilenameParameter() throws Exception {
        String expected = "attachment; filename=\"_xample.epub\"; filename*=UTF-8''%C9%87xample.epub";

        Method method = BookDownloadService.class.getDeclaredMethod("getContentDisposition", String.class);
        method.setAccessible(true);
        String actual = (String) method.invoke(bookDownloadService, "ɇxample.epub");

        assertEquals(expected, actual);
    }

    private BookEntity getSampleBook(String filename) {
        LibraryPathEntity libraryPathEntity = LibraryPathEntity
                .builder()
                .path("/library")
                .build();

        BookFileEntity bookFileEntity = BookFileEntity.builder()
                .fileName(filename)
                .fileSubPath("/subpath")
                .build();

        BookEntity bookEntity = BookEntity.builder()
                .bookFiles(List.of(bookFileEntity))
                .libraryPath(libraryPathEntity)
                .build();

        // Ensure we've set the identity here.
        bookFileEntity.setBook(bookEntity);

        return bookEntity;
    }
}
