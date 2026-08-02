package org.booklore.service.book;

import org.booklore.exception.APIException;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.request.CreatePhysicalBookRequest;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.CategoryRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.author.AuthorLocalResolver;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhysicalBookServiceTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private BookMapper bookMapper;
    @Mock
    private FileService fileService;
    @Mock
    private AuthorLocalResolver authorLocalResolver;

    private PhysicalBookService service;

    @BeforeEach
    void setUp() {
        service = new PhysicalBookService(
                bookRepository,
                libraryRepository,
                categoryRepository,
                bookMapper,
                fileService,
                authorLocalResolver);
    }

    @Test
    void createsBookWithMetadataAuthorsCategoriesAndCover() {
        LibraryEntity library = LibraryEntity.builder().id(7L).name("Library").build();
        AuthorEntity author = AuthorEntity.builder().id(11L).name("Author").build();
        CategoryEntity existingCategory = CategoryEntity.builder().id(13L).name("Existing").build();
        Book mapped = Book.builder().id(17L).build();
        CreatePhysicalBookRequest request = requestBuilder()
                .isbn("978-1-4028-9462-6")
                .publishedDate("2024-05-06")
                .authors(List.of("Author", "Alias"))
                .categories(List.of("Existing", "New"))
                .thumbnailUrl("https://example.test/cover.jpg")
                .build();

        when(libraryRepository.findById(7L)).thenReturn(Optional.of(library));
        when(authorLocalResolver.resolve("Author")).thenReturn(Optional.of(author));
        when(authorLocalResolver.resolve("Alias")).thenReturn(Optional.of(author));
        when(categoryRepository.findByName("Existing")).thenReturn(Optional.of(existingCategory));
        when(categoryRepository.findByName("New")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(CategoryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(bookRepository.save(any(BookEntity.class))).thenAnswer(invocation -> {
            BookEntity entity = invocation.getArgument(0);
            entity.setId(17L);
            return entity;
        });
        when(bookMapper.toBook(any(BookEntity.class))).thenReturn(mapped);

        assertThat(service.createPhysicalBook(request)).isSameAs(mapped);

        ArgumentCaptor<BookEntity> captor = ArgumentCaptor.forClass(BookEntity.class);
        verify(bookRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        BookEntity entity = captor.getValue();
        assertThat(entity.getLibrary()).isSameAs(library);
        assertThat(entity.getIsPhysical()).isTrue();
        assertThat(entity.getMetadata().getPublishedDate()).isEqualTo(LocalDate.of(2024, Month.MAY, 6));
        assertThat(entity.getMetadata().getIsbn13()).isEqualTo("9781402894626");
        assertThat(entity.getMetadata().getIsbn10()).isNull();
        assertThat(entity.getMetadata().getAuthors()).containsExactly(author);
        assertThat(entity.getMetadata().getCategories())
                .extracting(CategoryEntity::getName)
                .containsExactlyInAnyOrder("Existing", "New");
        assertThat(entity.getMetadata().getCoverUpdatedOn()).isNotNull();
        assertThat(entity.getBookCoverHash()).isNotBlank();
        verify(fileService).createThumbnailFromUrl(17L, "https://example.test/cover.jpg");
    }

    @Test
    void acceptsYearAndIsbn10WithoutOptionalCollectionsOrCover() {
        LibraryEntity library = LibraryEntity.builder().id(7L).name("Library").build();
        CreatePhysicalBookRequest request = requestBuilder()
                .isbn("0-306-40615-2")
                .publishedDate(" 1999 ")
                .authors(null)
                .categories(List.of())
                .thumbnailUrl(" ")
                .build();
        when(libraryRepository.findById(7L)).thenReturn(Optional.of(library));
        when(bookRepository.save(any(BookEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createPhysicalBook(request);

        ArgumentCaptor<BookEntity> captor = ArgumentCaptor.forClass(BookEntity.class);
        verify(bookMapper).toBook(captor.capture());
        assertThat(captor.getValue().getMetadata().getPublishedDate())
                .isEqualTo(LocalDate.of(1999, Month.JANUARY, 1));
        assertThat(captor.getValue().getMetadata().getIsbn10()).isEqualTo("0306406152");
        assertThat(captor.getValue().getMetadata().getIsbn13()).isNull();
        verify(fileService, never()).createThumbnailFromUrl(anyLong(), anyString());
    }

    @Test
    void toleratesInvalidOptionalMetadataAndCoverDownloadFailure() {
        LibraryEntity library = LibraryEntity.builder().id(7L).name("Library").build();
        CreatePhysicalBookRequest request = requestBuilder()
                .isbn(null)
                .publishedDate("not-a-date")
                .thumbnailUrl("https://example.test/broken.jpg")
                .build();
        when(libraryRepository.findById(7L)).thenReturn(Optional.of(library));
        when(bookRepository.save(any(BookEntity.class))).thenAnswer(invocation -> {
            BookEntity entity = invocation.getArgument(0);
            entity.setId(19L);
            return entity;
        });
        doThrow(new IllegalStateException("download failed"))
                .when(fileService).createThumbnailFromUrl(19L, request.getThumbnailUrl());

        service.createPhysicalBook(request);

        ArgumentCaptor<BookEntity> captor = ArgumentCaptor.forClass(BookEntity.class);
        verify(bookMapper).toBook(captor.capture());
        assertThat(captor.getValue().getMetadata().getPublishedDate()).isNull();
        assertThat(captor.getValue().getMetadata().getIsbn10()).isNull();
        assertThat(captor.getValue().getMetadata().getIsbn13()).isNull();
        assertThat(captor.getValue().getBookCoverHash()).isNull();
    }

    @Test
    void rejectsUnknownLibrary() {
        CreatePhysicalBookRequest request = requestBuilder().build();
        when(libraryRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPhysicalBook(request))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Library not found with id: 7");
        verify(bookRepository, never()).save(any());
    }

    @Test
    void togglesPhysicalFlagAndRejectsUnknownBook() {
        BookEntity entity = BookEntity.builder().id(23L).isPhysical(false).build();
        Book mapped = Book.builder().id(23L).isPhysical(true).build();
        when(bookRepository.findByIdWithBookFiles(23L)).thenReturn(Optional.of(entity));
        when(bookMapper.toBook(entity)).thenReturn(mapped);

        assertThat(service.togglePhysicalFlag(23L, true)).isSameAs(mapped);
        assertThat(entity.getIsPhysical()).isTrue();
        verify(bookRepository).save(entity);

        when(bookRepository.findByIdWithBookFiles(24L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.togglePhysicalFlag(24L, false))
                .isInstanceOf(APIException.class);
    }

    private CreatePhysicalBookRequest.CreatePhysicalBookRequestBuilder requestBuilder() {
        return CreatePhysicalBookRequest.builder()
                .libraryId(7L)
                .title("Title")
                .description("Description")
                .publisher("Publisher")
                .language("en")
                .pageCount(321);
    }
}
