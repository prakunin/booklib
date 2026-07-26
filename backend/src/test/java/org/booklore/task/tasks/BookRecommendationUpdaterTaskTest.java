package org.booklore.task.tasks;

import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.TaskType;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookEmbeddingVectorRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.book.BookQueryService;
import org.booklore.service.recommender.BookSemanticEmbeddingService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookRecommendationUpdaterTaskTest {

    @Mock
    private BookQueryService bookQueryService;
    @Mock
    private BookSemanticEmbeddingService semanticEmbeddingService;
    @Mock
    private BookEmbeddingVectorRepository embeddingRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private TaskCancellationManager cancellationManager;
    @InjectMocks
    private BookRecommendationUpdaterTask task;

    private BookLoreUser user;
    private TaskCreateRequest request;

    @BeforeEach
    void setUp() {
        user = BookLoreUser.builder()
                .permissions(new BookLoreUser.UserPermissions())
                .build();
        request = new TaskCreateRequest();
        request.setTaskId("task-123");
    }

    @Test
    void validatePermissionsThrowsWhenUserCannotAccessTaskManager() {
        user.getPermissions().setCanAccessTaskManager(false);
        assertThrows(APIException.class, () -> task.validatePermissions(user, request));
    }

    @Test
    void stagesInKeysetBatchesAndActivatesOnlyAfterFullCoverage() {
        BookEntity first = BookEntity.builder().id(10L).build();
        BookEntity second = BookEntity.builder().id(20L).build();
        when(bookQueryService.countAllNonDeleted()).thenReturn(2L);
        when(bookQueryService.getAllFullBookEntitiesAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(bookQueryService.getAllFullBookEntitiesAfterId(eq(20L), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(semanticEmbeddingService.updateEmbeddings(List.of(first, second))).thenReturn(Set.of(10L, 20L));
        when(semanticEmbeddingService.modelVersion()).thenReturn("qwen3-128-v1");
        when(embeddingRepository.countSemanticEmbeddingsForActiveBooks("qwen3-128-v1")).thenReturn(2L);
        when(embeddingRepository.activateSemantic("qwen3-128-v1")).thenReturn(true);

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskStatus.COMPLETED, response.getStatus());
        verify(bookQueryService).clearAllRecommendations();
        verify(notificationService, atLeastOnce()).sendMessage(eq(Topic.TASK_PROGRESS), any());
    }

    @Test
    void invalidatesRecommendationCacheWhenActiveEmbeddingsChange() {
        BookEntity book = BookEntity.builder().id(10L).build();
        when(bookQueryService.countAllNonDeleted()).thenReturn(1L);
        when(semanticEmbeddingService.modelVersion()).thenReturn("qwen3-128-v1");
        when(embeddingRepository.activeModel()).thenReturn("qwen3-128-v1");
        when(bookQueryService.getAllFullBookEntitiesAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(book));
        when(bookQueryService.getAllFullBookEntitiesAfterId(eq(10L), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(semanticEmbeddingService.updateEmbeddings(List.of(book))).thenReturn(Set.of(10L));
        when(embeddingRepository.countSemanticEmbeddingsForActiveBooks("qwen3-128-v1")).thenReturn(1L);

        task.execute(request);

        verify(bookQueryService).clearAllRecommendations();
    }

    @Test
    void cancellationLeavesStagingUntouchedAndDoesNotActivate() {
        when(bookQueryService.countAllNonDeleted()).thenReturn(5L);
        when(semanticEmbeddingService.modelVersion()).thenReturn("qwen3-128-v1");
        when(cancellationManager.isTaskCancelled("task-123")).thenReturn(true);

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskStatus.CANCELLED, response.getStatus());
        verify(bookQueryService, never()).getAllFullBookEntitiesAfterId(anyLong(), any(Pageable.class));
        verify(embeddingRepository, never()).activateSemantic(any());
        verify(bookQueryService, never()).clearAllRecommendations();
    }

    @Test
    void refusesActivationWhenCoverageIsIncomplete() {
        when(bookQueryService.countAllNonDeleted()).thenReturn(2L);
        when(semanticEmbeddingService.modelVersion()).thenReturn("qwen3-128-v1");
        when(bookQueryService.getAllFullBookEntitiesAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(embeddingRepository.countSemanticEmbeddingsForActiveBooks("qwen3-128-v1")).thenReturn(1L);

        assertThrows(IllegalStateException.class, () -> task.execute(request));

        verify(embeddingRepository, never()).activateSemantic(any());
        verify(bookQueryService, never()).clearAllRecommendations();
    }

    @Test
    void handlesEmptyLibrary() {
        when(bookQueryService.countAllNonDeleted()).thenReturn(0L);
        when(bookQueryService.getAllFullBookEntitiesAfterId(eq(0L), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(semanticEmbeddingService.modelVersion()).thenReturn("qwen3-128-v1");
        when(embeddingRepository.countSemanticEmbeddingsForActiveBooks("qwen3-128-v1")).thenReturn(0L);
        when(embeddingRepository.activateSemantic("qwen3-128-v1")).thenReturn(true);

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskType.UPDATE_BOOK_RECOMMENDATIONS, response.getTaskType());
        assertEquals(TaskStatus.COMPLETED, response.getStatus());
    }
}
