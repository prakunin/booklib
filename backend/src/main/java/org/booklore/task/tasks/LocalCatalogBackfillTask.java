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
import org.booklore.task.options.LocalCatalogBackfillOptions;
import org.springframework.stereotype.Component;

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
        LocalCatalogBackfillService.BackfillResult result;
        try {
            result = backfillService.run(
                    libraryId,
                    taskId,
                    () -> cancellationManager.isTaskCancelled(taskId),
                    processed -> sendProgress(taskId, UNKNOWN_PROGRESS,
                            "Enriched " + processed + " books from the local catalog", TaskStatus.IN_PROGRESS));
        } catch (RuntimeException e) {
            throw reportFailure(taskId, e);
        }

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
        LocalCatalogBackfillOptions options = request.getOptionsAs(LocalCatalogBackfillOptions.class);
        if (options != null && options.getLibraryId() != null) {
            return options.getLibraryId();
        }
        throw ApiError.GENERIC_BAD_REQUEST.createException(
                "Local catalog backfill requires a libraryId option");
    }

    /**
     * Emits the terminal frame a failed run owes the UI, then hands the exception back to be thrown.
     * <p>
     * {@code TaskService.executeAsyncTask} catches a failing task into task history and nothing else —
     * it sends no {@code TASK_PROGRESS} frame — so a run that dies after this task's opening
     * "Indexing the local catalog" frame left the INPX archive panel's {@code backfillRunning()}
     * computed permanently true: Run stayed disabled and the spinner kept turning until the user
     * reloaded the page. The panel clears on any terminal status, so one {@code FAILED} frame closes
     * it. This is deliberately scoped to this task rather than fixed in {@code TaskService}, whose
     * error handling is shared by every task type.
     * <p>
     * Catches {@code RuntimeException} rather than only the index-still-building refusal: any failure
     * out of the walk leaves the panel in exactly the same stuck state, and the refusal is merely its
     * most likely cause. The exception is returned rather than thrown here so the call site reads
     * {@code throw reportFailure(...)} and the compiler can still see that the branch does not fall
     * through.
     */
    private RuntimeException reportFailure(String taskId, RuntimeException failure) {
        log.warn("{}: Task {} failed: {}", getTaskType(), taskId, failure.getMessage());
        sendProgress(taskId, UNKNOWN_PROGRESS,
                "Local catalog backfill failed: " + failure.getMessage(), TaskStatus.FAILED);
        return failure;
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
