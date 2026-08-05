package org.booklore.task.tasks;

import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.booklore.service.enrichment.catalog.LocalCatalogBackfillService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.task.options.LocalCatalogBackfillOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalCatalogBackfillTaskTest {

    private final LocalCatalogBackfillService backfillService = mock(LocalCatalogBackfillService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final TaskCancellationManager cancellationManager = mock(TaskCancellationManager.class);

    private final LocalCatalogBackfillTask task =
            new LocalCatalogBackfillTask(backfillService, notificationService, cancellationManager);

    private TaskCreateRequest request(Object options) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setTaskId("task-1");
        request.setTaskType(TaskType.LOCAL_CATALOG_BACKFILL);
        request.setOptions(options);
        return request;
    }

    @Test
    void runsTheBackfillForTheRequestedLibrary() {
        when(backfillService.run(eq(19L), eq("task-1"), any(), any()))
                .thenReturn(new LocalCatalogBackfillService.BackfillResult(10, 0, false));

        TaskCreateResponse response = task.execute(
                request(LocalCatalogBackfillOptions.builder().libraryId(19L).build()));

        assertThat(response.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(backfillService).run(eq(19L), eq("task-1"), any(), any());
    }

    @Test
    void reportsCancellation() {
        when(backfillService.run(anyLong(), any(), any(), any()))
                .thenReturn(new LocalCatalogBackfillService.BackfillResult(3, 0, true));

        TaskCreateResponse response = task.execute(
                request(LocalCatalogBackfillOptions.builder().libraryId(19L).build()));

        assertThat(response.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void refusesToRunWithoutALibraryId() {
        assertThatThrownBy(() -> task.execute(request(LocalCatalogBackfillOptions.builder().build())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("libraryId");
    }

    @Test
    void refusesToRunWithoutAnyOptions() {
        assertThatThrownBy(() -> task.execute(request(null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("libraryId");
    }

    /**
     * {@code TaskService.executeAsyncTask} catches a failing task into task history and sends no
     * {@code TASK_PROGRESS} frame, so without this the INPX archive panel is left holding this task's
     * opening "Indexing the local catalog" {@code IN_PROGRESS} frame forever: {@code backfillRunning()}
     * stays true, Run stays disabled and the spinner keeps turning until the page is reloaded. The
     * panel clears on any terminal status, so the failure has to send one.
     * <p>
     * The refusal to walk while an index rebuild is in flight is the likely trigger — it is the one
     * failure a user can provoke by pressing Run at the wrong moment — but the frame is owed for any
     * failure out of the walk, which is why the assertion only cares that a terminal FAILED frame went
     * out and that the exception still propagates for task history to record.
     */
    @Test
    void tellsTheUiTheRunIsOverWhenTheBackfillRefusesToStart() {
        when(backfillService.run(anyLong(), any(), any(), any()))
                .thenThrow(new IllegalStateException("the index is being rebuilt"));

        assertThatThrownBy(() -> task.execute(
                request(LocalCatalogBackfillOptions.builder().libraryId(19L).build())))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<TaskProgressPayload> payloads = ArgumentCaptor.captor();
        verify(notificationService, atLeastOnce()).sendMessage(eq(Topic.TASK_PROGRESS), payloads.capture());
        assertThat(payloads.getAllValues())
                .extracting(TaskProgressPayload::getTaskStatus)
                .containsExactly(TaskStatus.IN_PROGRESS, TaskStatus.FAILED);
        assertThat(payloads.getAllValues().getLast().getMessage()).contains("the index is being rebuilt");
    }

    /**
     * {@code CAN_ACCESS_TASK_MANAGER} is a permission gate on a task that rewrites the metadata of
     * every archived book in a library, and six sibling task tests cover exactly this — this was the
     * only new task without it. Mirrors {@code FacetCountRecomputeTaskTest}.
     */
    @Test
    void validatePermissionsRejectsUsersWithoutTaskManagerAccess() {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        BookLoreUser user = BookLoreUser.builder().id(1L).permissions(permissions).build();
        TaskCreateRequest request = request(LocalCatalogBackfillOptions.builder().libraryId(19L).build());

        assertThatThrownBy(() -> task.validatePermissions(user, request))
                .isInstanceOf(APIException.class);
    }

    @Test
    void validatePermissionsAllowsUsersWithTaskManagerAccess() {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setCanAccessTaskManager(true);
        BookLoreUser user = BookLoreUser.builder().id(1L).permissions(permissions).build();
        TaskCreateRequest request = request(LocalCatalogBackfillOptions.builder().libraryId(19L).build());

        assertThatCode(() -> task.validatePermissions(user, request)).doesNotThrowAnyException();
    }
}
