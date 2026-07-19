package org.booklore.service.komga;

import org.booklore.mapper.komga.KomgaMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.komga.KomgaBookDto;
import org.booklore.model.dto.komga.KomgaPageDto;
import org.booklore.model.dto.komga.KomgaPageableDto;
import org.booklore.model.dto.komga.KomgaSeriesDto;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.MagicShelfService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.reader.CbxReaderService;
import org.booklore.service.reader.PdfReaderService;
import org.booklore.service.restriction.ContentRestrictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KomgaServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private KomgaMapper komgaMapper;
    
    @Mock
    private MagicShelfService magicShelfService;
    
    @Mock
    private CbxReaderService cbxReaderService;

    @Mock
    private PdfReaderService pdfReaderService;
    
    @Mock
    private AppSettingService appSettingService;

    @Mock
    private ContentRestrictionService contentRestrictionService;

    @InjectMocks
    private KomgaService komgaService;

    private LibraryEntity library;
    private List<BookEntity> seriesBooks;

    @BeforeEach
    void setUp() {
        library = new LibraryEntity();
        library.setId(1L);
        
        // Mock app settings (lenient because not all tests use this)
        AppSettings appSettings = new AppSettings();
        appSettings.setKomgaGroupUnknown(true);
        lenient().when(appSettingService.getAppSettings()).thenReturn(appSettings);

        // Create multiple books for testing pagination
        seriesBooks = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            BookMetadataEntity metadata = BookMetadataEntity.builder()
                    .title("Book " + i)
                    .seriesName("Test Series")
                    .seriesNumber((float) i)
                    .pageCount(null)  // Test null pageCount
                    .build();

            BookEntity book = new BookEntity();
            book.setId((long) i);
            book.setLibrary(library);
            book.setMetadata(metadata);
            book.setAddedOn(Instant.now());

            BookFileEntity pdf = new BookFileEntity();
            pdf.setId((long) i);
            pdf.setBook(book);
            pdf.setFileSubPath("author/title");
            pdf.setFileName("book-" + i + ".pdf");
            pdf.setBookType(BookFileType.PDF);
            pdf.setBookFormat(true);

            book.setBookFiles(List.of(pdf));

            seriesBooks.add(book);
        }
    }

    @Test
    void shouldReturnAllBooksWhenUnpagedIsTrue() {
        // Given
        when(komgaMapper.getUnknownSeriesName()).thenReturn("Unknown Series");
        when(bookRepository.findGroupedSeriesNameByLibraryIdAndSlug(
                eq(1L), eq("Unknown Series"), eq("test-series"), any(Pageable.class)))
                .thenReturn(List.of("Test Series"));
        when(bookRepository.findBooksPageBySeriesNameGroupedByLibraryId(
                eq("Test Series"), eq(1L), eq("Unknown Series"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(seriesBooks));
        
        // Mock the mapper to return DTOs
        for (BookEntity book : seriesBooks) {
            KomgaBookDto dto = KomgaBookDto.builder()
                    .id(book.getId().toString())
                    .name(book.getMetadata().getTitle())
                    .build();
            when(komgaMapper.toKomgaBookDto(book)).thenReturn(dto);
        }

        // When: Request with unpaged=true
        KomgaPageableDto<KomgaBookDto> result = komgaService.getBooksBySeries("1-test-series", 0, 20, true);

        // Then: Should return all 50 books
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(50);
        assertThat(result.getTotalElements()).isEqualTo(50);
        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(50);
        assertThat(result.getNumber()).isZero();
        verify(bookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(anyLong(), anyString());
        verify(bookRepository, never()).findBooksBySeriesNameGroupedByLibraryId(anyString(), anyLong(), anyString());
    }

    @Test
    void shouldReturnPagedBooksWhenUnpagedIsFalse() {
        // Given
        when(komgaMapper.getUnknownSeriesName()).thenReturn("Unknown Series");
        when(bookRepository.findGroupedSeriesNameByLibraryIdAndSlug(
                eq(1L), eq("Unknown Series"), eq("test-series"), any(Pageable.class)))
                .thenReturn(List.of("Test Series"));
        when(bookRepository.findBooksPageBySeriesNameGroupedByLibraryId(
                eq("Test Series"), eq(1L), eq("Unknown Series"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(seriesBooks.subList(0, 20), PageRequest.of(0, 20), seriesBooks.size()));
        
        // Mock the mapper to return DTOs (only for the books that will be used)
        for (int i = 0; i < 20; i++) {
            BookEntity book = seriesBooks.get(i);
            KomgaBookDto dto = KomgaBookDto.builder()
                    .id(book.getId().toString())
                    .name(book.getMetadata().getTitle())
                    .build();
            when(komgaMapper.toKomgaBookDto(book)).thenReturn(dto);
        }

        // When: Request with unpaged=false and page size 20
        KomgaPageableDto<KomgaBookDto> result = komgaService.getBooksBySeries("1-test-series", 0, 20, false);

        // Then: Should return first page with 20 books
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(20);
        assertThat(result.getTotalElements()).isEqualTo(50);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getNumber()).isZero();
        verify(bookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(anyLong(), anyString());
        verify(bookRepository, never()).findBooksBySeriesNameGroupedByLibraryId(anyString(), anyLong(), anyString());
    }

    @Test
    void shouldHandleNullPageCountInGetBookPages() {
        // Given: Book with null pageCount
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .title("Test Book")
                .pageCount(null)
                .build();

        BookEntity book = new BookEntity();
        book.setId(100L);
        book.setMetadata(metadata);

        when(bookRepository.findByIdWithBookFiles(100L)).thenReturn(Optional.of(book));

        // When: Get book pages
        List<KomgaPageDto> pages = komgaService.getBookPages(100L);

        // Then: Should return empty list without throwing NPE
        assertThat(pages).isNotNull().isEmpty();
    }

    @Test
    void shouldReturnCorrectPagesWhenPageCountIsValid() {
        // Given: Book with valid pageCount
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .title("Test Book")
                .pageCount(5)
                .build();

        BookEntity book = new BookEntity();
        book.setId(100L);
        book.setMetadata(metadata);

        when(bookRepository.findByIdWithBookFiles(100L)).thenReturn(Optional.of(book));

        // When: Get book pages
        List<KomgaPageDto> pages = komgaService.getBookPages(100L);

        // Then: Should return 5 pages
        assertThat(pages).isNotNull().hasSize(5);
        assertThat(pages.get(0).getNumber()).isEqualTo(1);
        assertThat(pages.get(4).getNumber()).isEqualTo(5);
    }

    @Test
    void streamBookPageImage_streamsCbxPageWithoutPrefetchingOrBuffering() throws Exception {
        BookEntity book = getBookEntityWithPrimaryFile(BookFileType.CBX);
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
        doAnswer(invocation -> {
            OutputStream outputStream = invocation.getArgument(2);
            outputStream.write(new byte[]{1, 2, 3});
            return null;
        }).when(cbxReaderService).streamPageImage(eq(1L), eq(2), any(OutputStream.class));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        komgaService.streamBookPageImage(1L, 2, outputStream);

        assertThat(outputStream.toByteArray()).containsExactly(1, 2, 3);
        verify(cbxReaderService).streamPageImage(eq(1L), eq(2), same(outputStream));
        verify(cbxReaderService, never()).getAvailablePages(anyLong());
        verifyNoInteractions(pdfReaderService);
    }

    @Test
    void streamBookPageImageAsPng_streamsConvertedPngThroughExistingPageStream() throws Exception {
        BookEntity book = getBookEntityWithPrimaryFile(BookFileType.CBX);
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

        byte[] sourceImage = createPngImageBytes();
        doAnswer(invocation -> {
            OutputStream outputStream = invocation.getArgument(2);
            outputStream.write(sourceImage);
            return null;
        }).when(cbxReaderService).streamPageImage(eq(1L), eq(2), any(OutputStream.class));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        komgaService.streamBookPageImageAsPng(1L, 2, outputStream);

        byte[] convertedImage = outputStream.toByteArray();
        assertThat(convertedImage).startsWith(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        assertThat(ImageIO.read(new ByteArrayInputStream(convertedImage))).isNotNull();
        verify(cbxReaderService).streamPageImage(eq(1L), eq(2), any(OutputStream.class));
        verify(cbxReaderService, never()).getAvailablePages(anyLong());
        verifyNoInteractions(pdfReaderService);
    }

    @Test
    void shouldGetAllSeriesOptimized() {
        // Given: Mock the optimized repository method
        List<String> seriesNames = List.of("Series A", "Series B", "Series C");
        when(bookRepository.findDistinctSeriesNamesGroupedByLibraryId(anyLong(), anyString()))
                .thenReturn(seriesNames);
        
        // Mock books for the first page (Series A and Series B only)
        List<BookEntity> seriesABooks = List.of(seriesBooks.get(0), seriesBooks.get(1));
        List<BookEntity> seriesBBooks = List.of(seriesBooks.get(2), seriesBooks.get(3));
        
        List<BookEntity> pageBooks = new ArrayList<>();
        pageBooks.addAll(seriesABooks);
        pageBooks.addAll(seriesBBooks);

        when(bookRepository.findBooksBySeriesNamesGroupedByLibraryId(
                List.of("Series A", "Series B"), 1L, "Unknown Series", false))
                .thenReturn(pageBooks);
        
        when(komgaMapper.getUnknownSeriesName()).thenReturn("Unknown Series");
        seriesABooks.forEach(book -> when(komgaMapper.getBookSeriesName(book)).thenReturn("Series A"));
        seriesBBooks.forEach(book -> when(komgaMapper.getBookSeriesName(book)).thenReturn("Series B"));
        when(komgaMapper.toKomgaSeriesDto(eq("Series A"), anyLong(), any()))
                .thenReturn(KomgaSeriesDto.builder().id("1-series-a").name("Series A").booksCount(2).build());
        when(komgaMapper.toKomgaSeriesDto(eq("Series B"), anyLong(), any()))
                .thenReturn(KomgaSeriesDto.builder().id("1-series-b").name("Series B").booksCount(2).build());
        
        // When: Request first page with size 2
        KomgaPageableDto<KomgaSeriesDto> result = komgaService.getAllSeries(1L, 0, 2, false);
        
        // Then: Should return only 2 series (not all 3)
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getNumber()).isZero();
        assertThat(result.getFirst()).isTrue();
        assertThat(result.getLast()).isFalse();
        
        // Verify that only books for Series A and B were loaded (optimization check)
        verify(bookRepository, never()).findAllWithMetadataByLibraryId(anyLong());
        verify(bookRepository, never()).findAllWithMetadata();
        verify(bookRepository, never()).findBooksBySeriesNameGroupedByLibraryId(anyString(), anyLong(), anyString());
    }

    @Test
    void validateBookContentAccess_acceptsBookCorrectWithAccess() {
        BookEntity bookEntity = getBookEntity();
        BookLoreUser user = getBookloreUser(false, List.of(library));

        when(bookRepository.findById(1L)).thenReturn(Optional.of(bookEntity));
        when(
                contentRestrictionService.applyRestrictions(List.of(bookEntity), user.getId())
        ).thenReturn(List.of(bookEntity));

        boolean actual = komgaService.validateBookContentAccess(user, 1L);

        assertThat(actual).isTrue();
    }

    @Test
    void validateBookContentAccess_ignoresNoPermissionUser() {
        BookEntity bookEntity = getBookEntity();
        BookLoreUser user = BookLoreUser.builder()
                .id(1L)
                .assignedLibraries(List.of(Library.builder().id(1L).build()))
                .build();

        when(bookRepository.findById(1L)).thenReturn(Optional.of(bookEntity));
        when(
                contentRestrictionService.applyRestrictions(List.of(bookEntity), user.getId())
        ).thenReturn(List.of(bookEntity));

        boolean actual = komgaService.validateBookContentAccess(user, 1L);

        assertThat(actual).isTrue();
    }

    @Test
    void validateBookContentAccess_rejectsNullLibraryUser() {
        BookEntity bookEntity = getBookEntity();
        BookLoreUser user = BookLoreUser.builder()
                .build();

        when(bookRepository.findById(1L)).thenReturn(Optional.of(bookEntity));

        boolean actual = komgaService.validateBookContentAccess(user, 1L);

        assertThat(actual).isFalse();

        verifyNoInteractions(contentRestrictionService);
    }

    @Test
    void validateBookContentAccess_rejectsOnLibraryAccess() {
        LibraryEntity otherLibrary = LibraryEntity.builder().id(2L).build();

        BookEntity bookEntity = getBookEntity();
        BookLoreUser user = getBookloreUser(false, List.of(otherLibrary));

        when(bookRepository.findById(1L)).thenReturn(Optional.of(bookEntity));

        boolean actual = komgaService.validateBookContentAccess(user, 1L);

        assertThat(actual).isFalse();

        verifyNoInteractions(contentRestrictionService);
    }

    @Test
    void validateBookContentAccess_rejectsOnContentRestrictions() {
        BookEntity bookEntity = getBookEntity();
        BookLoreUser user = getBookloreUser(false, List.of(library));

        when(bookRepository.findById(1L)).thenReturn(Optional.of(bookEntity));
        when(
                contentRestrictionService.applyRestrictions(List.of(bookEntity), user.getId())
        ).thenReturn(List.of());

        boolean actual = komgaService.validateBookContentAccess(user, 1L);

        assertThat(actual).isFalse();
    }

    @Test
    void validateBookContentAccess_rejectsOnMissingBook() {
        BookLoreUser user = getBookloreUser(false, List.of());

        boolean actual = komgaService.validateBookContentAccess(user, 1L);

        assertThat(actual).isFalse();
    }

    @Test
    void validateBookContentAccess_adminAlwaysHasAccess() {
        BookLoreUser user = getBookloreUser(true, List.of());

        boolean actual = komgaService.validateBookContentAccess(user, 1L);

        assertThat(actual).isTrue();
    }

    BookEntity getBookEntity() {
        return BookEntity.builder()
                .id(1L)
                .library(library)
                .build();
    }

    private BookEntity getBookEntityWithPrimaryFile(BookFileType bookFileType) {
        BookEntity bookEntity = getBookEntity();
        BookFileEntity bookFile = new BookFileEntity();
        bookFile.setId(1L);
        bookFile.setBook(bookEntity);
        bookFile.setBookType(bookFileType);
        bookFile.setBookFormat(true);
        bookEntity.setBookFiles(List.of(bookFile));
        return bookEntity;
    }

    private byte[] createPngImageBytes() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xff6600);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream.toByteArray();
    }

    BookLoreUser getBookloreUser(boolean isAdmin, List<LibraryEntity> libraries) {
        BookLoreUser.UserPermissions perms = new BookLoreUser.UserPermissions();

        perms.setAdmin(isAdmin);

        return BookLoreUser.builder()
                .id(1L)
                .permissions(perms)
                .assignedLibraries(
                        libraries
                                .stream()
                                .map(l -> Library.builder().id(l.getId()).build())
                                .toList()
                )
                .build();
    }
}
