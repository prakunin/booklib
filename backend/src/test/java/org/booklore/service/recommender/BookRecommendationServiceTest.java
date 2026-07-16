package org.booklore.service.recommender;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookRecommendationServiceTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookQueryService bookQueryService;
    @Mock
    private BookMapper bookMapper;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private BookRecommendationQueueService recommendationQueue;

    private BookRecommendationService service;

    @BeforeEach
    void setUp() {
        service = new BookRecommendationService(
                bookRepository, bookQueryService, bookMapper, authenticationService, recommendationQueue);
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder()
                .username("alice")
                .permissions(permissions)
                .assignedLibraries(Collections.emptyList())
                .build());
    }

    @Test
    void queuesMissingRecommendationsWithoutRunningSynchronousFallback() {
        BookEntity book = BookEntity.builder().id(42L).similarBooksJson(null).build();
        when(bookRepository.findByIdWithMetadata(42L)).thenReturn(Optional.of(book));

        assertThat(service.getRecommendations(42L, 20)).isEmpty();

        verify(recommendationQueue).enqueue(42L, "alice");
        verifyNoInteractions(bookQueryService, bookMapper);
    }

    @Test
    void treatsAnEmptyStoredSetAsACompletedComputation() {
        BookEntity book = BookEntity.builder().id(42L).similarBooksJson(Collections.emptySet()).build();
        when(bookRepository.findByIdWithMetadata(42L)).thenReturn(Optional.of(book));

        assertThat(service.getRecommendations(42L, 20)).isEmpty();

        verifyNoInteractions(recommendationQueue, bookQueryService, bookMapper);
    }
}
