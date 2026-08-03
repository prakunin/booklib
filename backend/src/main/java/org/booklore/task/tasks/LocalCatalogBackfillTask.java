package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.booklore.service.enrichment.catalog.LocalCatalogBackfillService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Drives {@link LocalCatalogBackfillService} for a single library, chosen by the caller via the
 * {@code libraryId} option. There is no "all libraries" mode: the catalog a backfill reads is a
 * per-library setting, so running against every library at once has no sensible meaning.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocalCatalogBackfillTask implements Task {

    /**
     * The walk has no notion of a total book count up front — it pages through an unbounded
     * archive/entry cursor until it runs dry — so there is no honest percentage to report while it
     * runs. Rather than invent one (or send a raw {@code -1}, which {@code TaskProgressPayload}
     * documents as a 0-100 percentage and the task-manager UI renders verbatim as {@code "-1%"}),
     * progress is held at the last known value — 0 while in flight — and the message text carries
     * the live processed count instead. It only moves once, to 100, on completion.
     */
    private static final int UNKNOWN_PROGRESS = 0;

    private final LocalCatalogBackfillService backfillService;
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
        long libraryId = requireLibraryId(request);
        String taskId = request.getTaskId();
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(taskId)
                .taskType(TaskType.LOCAL_CATALOG_BACKFILL);

        sendProgress(taskId, UNKNOWN_PROGRESS, "Indexing the local catalog", TaskStatus.IN_PROGRESS);
        LocalCatalogBackfillService.BackfillResult result = backfillService.run(
                libraryId,
                taskId,
                () -> cancellationManager.isTaskCancelled(taskId),
                processed -> sendProgress(taskId, UNKNOWN_PROGRESS,
                        "Enriched " + processed + " books from the local catalog", TaskStatus.IN_PROGRESS));

        if (result.cancelled()) {
            return buildCancelledResponse(builder, taskId);
        }

        log.info("{}: Task {} completed for library {}: {} processed, {} failed",
                getTaskType(), taskId, libraryId, result.processed(), result.failed());
        sendProgress(taskId, 100,
                "Local catalog backfill finished: " + result.processed() + " processed, "
                        + result.failed() + " failed",
                TaskStatus.COMPLETED);
        return builder.status(TaskStatus.COMPLETED).build();
    }

    /**
     * The backfill is per-library by design: the catalog it reads is a library setting, so there is
     * no sensible "all libraries" run.
     */
    private long requireLibraryId(TaskCreateRequest request) {
        if (request.getOptions() instanceof Map<?, ?> options
                && options.get("libraryId") instanceof Number libraryId) {
            return libraryId.longValue();
        }
        throw ApiError.GENERIC_BAD_REQUEST.createException(
                "Local catalog backfill requires a libraryId option");
    }

    private TaskCreateResponse buildCancelledResponse(
            TaskCreateResponse.TaskCreateResponseBuilder builder, String taskId) {
        log.info("{}: Task {} cancelled", getTaskType(), taskId);
        sendProgress(taskId, UNKNOWN_PROGRESS, "Local catalog backfill cancelled", TaskStatus.CANCELLED);
        return builder.status(TaskStatus.CANCELLED).build();
    }

    private void sendProgress(String taskId, int progress, String message, TaskStatus taskStatus) {
        try {
            notificationService.sendMessage(
                    Topic.TASK_PROGRESS,
                    TaskProgressPayload.builder()
                            .taskId(taskId)
                            .taskType(TaskType.LOCAL_CATALOG_BACKFILL)
                            .message(message)
                            .progress(progress)
                            .taskStatus(taskStatus)
                            .build());
        } catch (Exception exception) {
            log.error("Failed to send task progress notification for taskId={}", taskId, exception);
        }
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.LOCAL_CATALOG_BACKFILL;
    }
}
