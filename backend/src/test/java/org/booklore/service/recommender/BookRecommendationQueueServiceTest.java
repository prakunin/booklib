package org.booklore.service.recommender;

import org.booklore.exception.APIException;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookRecommendationQueueServiceTest {

    @Mock
    private BookRecommendationComputationService computationService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AsyncTaskExecutor executor;

    private BookRecommendationQueueService service;

    @BeforeEach
    void setUp() {
        service = new BookRecommendationQueueService(computationService, notificationService, executor);
    }

    @Test
    void deduplicatesQueuedBooksAndNotifiesEveryWaitingUser() {
        ArgumentCaptor<Runnable> jobCaptor = ArgumentCaptor.forClass(Runnable.class);

        service.enqueue(42L, "alice");
        service.enqueue(42L, "bob");

        verify(executor).execute(jobCaptor.capture());
        jobCaptor.getValue().run();

        verify(computationService).computeAndStore(42L, 25);
        verify(notificationService).sendMessageToUser("alice", Topic.BOOK_RECOMMENDATIONS_UPDATE, 42L);
        verify(notificationService).sendMessageToUser("bob", Topic.BOOK_RECOMMENDATIONS_UPDATE, 42L);
    }

    @Test
    void returnsServiceUnavailableWhenTheBoundedQueueIsFull() {
        doThrow(new TaskRejectedException("full")).when(executor).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.enqueue(42L, "alice"))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("queue is full");
    }
}
