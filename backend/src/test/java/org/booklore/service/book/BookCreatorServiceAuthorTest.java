package org.booklore.service.book;

import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.author.AuthorLocalResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookCreatorServiceAuthorTest {

    @Test
    void resolvesAuthorsViaResolverAndSkipsRejectedTokens() {
        AuthorLocalResolver resolver = mock(AuthorLocalResolver.class);
        when(resolver.resolve("Valid Author"))
                .thenReturn(Optional.of(AuthorEntity.builder().id(1L).name("Valid Author").build()));
        when(resolver.resolve("   ")).thenReturn(Optional.empty());

        BookCreatorService service = BookCreatorServiceTestFactory.withResolver(resolver);
        BookEntity book = BookEntity.builder().metadata(BookMetadataEntity.builder().build()).build();

        service.addAuthorsToBook(List.of("Valid Author", "   "), book);

        assertThat(book.getMetadata().getAuthors())
                .extracting(AuthorEntity::getName)
                .containsExactly("Valid Author");
        verify(resolver).resolve("Valid Author");
    }
}
