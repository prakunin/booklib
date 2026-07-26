package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookEmbeddingVectorRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.book.BookQueryService;
import org.booklore.service.recommender.BookSemanticEmbeddingService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookRecommendationUpdaterTask implements Task {

    private static final int BATCH_SIZE = 64;
    private static final long MIN_NOTIFICATION_INTERVAL_MS = 250;

    private final BookQueryService bookQueryService;
    private final BookSemanticEmbeddingService semanticEmbeddingService;
    private final BookEmbeddingVectorRepository embeddingRepository;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(request.getTaskId())
                .taskType(TaskType.UPDATE_BOOK_RECOMMENDATIONS);
        String taskId = request.getTaskId();
        long startedAt = System.currentTimeMillis();
        long totalBooks = bookQueryService.countAllNonDeleted();
        String modelVersion = semanticEmbeddingService.modelVersion();
        boolean currentModelWasActive = Objects.equals(modelVersion, embeddingRepository.activeModel());
        long processedBooks = 0;
        long changedBooks = 0;
        long afterId = 0;
        long lastNotificationTime = sendTaskProgressNotification(
                taskId, 0, "Starting semantic embedding update", TaskStatus.IN_PROGRESS, 0, true);

        while (true) {
            if (cancellationManager.isTaskCancelled(taskId)) {
                return buildCancelledResponse(builder, taskId, lastNotificationTime);
            }
            List<BookEntity> batch = bookQueryService.getAllFullBookEntitiesAfterId(
                    afterId, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }

            Set<Long> changedIds = semanticEmbeddingService.updateEmbeddings(batch);
            changedBooks += changedIds.size();
            processedBooks += batch.size();
            afterId = batch.getLast().getId();

            int progress = totalBooks == 0
                    ? 95
                    : Math.min(95, 2 + (int) (processedBooks * 93L / totalBooks));
            lastNotificationTime = sendTaskProgressNotification(
                    taskId,
                    progress,
                    String.format("Generated semantic embeddings: %d/%d books", processedBooks, totalBooks),
                    TaskStatus.IN_PROGRESS,
                    lastNotificationTime,
                    false);
        }

        if (cancellationManager.isTaskCancelled(taskId)) {
            return buildCancelledResponse(builder, taskId, lastNotificationTime);
        }

        long storedBooks = embeddingRepository.countSemanticEmbeddingsForActiveBooks(modelVersion);
        if (storedBooks != totalBooks) {
            throw new IllegalStateException(
                    "Semantic embedding coverage mismatch: expected " + totalBooks + ", found " + storedBooks);
        }

        boolean activated = embeddingRepository.activateSemantic(modelVersion);
        if (activated || currentModelWasActive && changedBooks > 0) {
            bookQueryService.clearAllRecommendations();
        }

        long duration = System.currentTimeMillis() - startedAt;
        log.info("{}: Task completed. Processed {}, changed {}, activated {}, duration {} ms",
                getTaskType(), processedBooks, changedBooks, activated, duration);
        sendTaskProgressNotification(
                taskId,
                100,
                String.format("Semantic embeddings ready: %d processed, %d changed in %d ms",
                        processedBooks, changedBooks, duration),
                TaskStatus.COMPLETED,
                lastNotificationTime,
                true);
        return builder.status(TaskStatus.COMPLETED).build();
    }

    private TaskCreateResponse buildCancelledResponse(
            TaskCreateResponse.TaskCreateResponseBuilder builder,
            String taskId,
            long lastNotificationTime) {
        log.info("{}: Task {} cancelled; staged embeddings remain available for resume", getTaskType(), taskId);
        sendTaskProgressNotification(
                taskId,
                0,
                "Semantic embedding update cancelled; progress will resume on the next run",
                TaskStatus.CANCELLED,
                lastNotificationTime,
                true);
        return builder.status(TaskStatus.CANCELLED).build();
    }

    private long sendTaskProgressNotification(
            String taskId,
            int progress,
            String message,
            TaskStatus taskStatus,
            long lastNotificationTime,
            boolean force) {
        long currentTime = System.currentTimeMillis();
        if (force || currentTime - lastNotificationTime >= MIN_NOTIFICATION_INTERVAL_MS) {
            try {
                notificationService.sendMessage(
                        Topic.TASK_PROGRESS,
                        TaskProgressPayload.builder()
                                .taskId(taskId)
                                .taskType(TaskType.UPDATE_BOOK_RECOMMENDATIONS)
                                .message(message)
                                .progress(progress)
                                .taskStatus(taskStatus)
                                .build());
                return currentTime;
            } catch (Exception exception) {
                log.error("Failed to send task progress notification for taskId={}", taskId, exception);
            }
        }
        return lastNotificationTime;
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.UPDATE_BOOK_RECOMMENDATIONS;
    }
}
