package org.booklore.service.inpx;

import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookCoverUpdateProjection;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.BookCoverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InpxCoverGenerationListenerTest {

    @Mock private BookCoverService bookCoverService;
    @Mock private BookRepository bookRepository;
    @Mock private NotificationService notificationService;

    private InpxCoverGenerationListener listener;

    @BeforeEach
    void setUp() {
        listener = new InpxCoverGenerationListener(bookCoverService, bookRepository, notificationService);
    }

    @Test
    void generatesMissingCoverAndNotifiesRequestingUser() {
        BookCoverUpdateProjection update = mock(BookCoverUpdateProjection.class);
        when(bookCoverService.tryGenerateMissingInpxCover(42L)).thenReturn(true);
        when(bookRepository.findCoverUpdateInfoByIds(List.of(42L))).thenReturn(List.of(update));

        listener.handle(new InpxCoverGenerationRequestedEvent(Set.of(42L), "alice"));

        verify(bookCoverService).tryGenerateMissingInpxCover(42L);
        verify(notificationService).sendMessageToUser(
                "alice", Topic.BOOKS_COVER_UPDATE, List.of(update));
    }

    @Test
    void doesNotRepeatBooksAlreadyAttemptedWithoutAnEmbeddedCover() {
        when(bookCoverService.tryGenerateMissingInpxCover(42L)).thenReturn(false);
        InpxCoverGenerationRequestedEvent event =
                new InpxCoverGenerationRequestedEvent(Set.of(42L), "alice");

        listener.handle(event);
        listener.handle(event);

        verify(bookCoverService).tryGenerateMissingInpxCover(42L);
        verifyNoInteractions(bookRepository, notificationService);
    }

    @Test
    void retriesAfterTransientFailure() {
        when(bookCoverService.tryGenerateMissingInpxCover(42L))
                .thenThrow(new IllegalStateException("archive offline"))
                .thenReturn(false);
        InpxCoverGenerationRequestedEvent event =
                new InpxCoverGenerationRequestedEvent(Set.of(42L), "alice");

        listener.handle(event);
        listener.handle(event);

        verify(bookCoverService, times(2)).tryGenerateMissingInpxCover(42L);
    }
}
