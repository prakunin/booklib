package org.booklore.task.tasks;

import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.NotificationService;
import org.booklore.service.enrichment.catalog.LocalCatalogBackfillService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.task.options.LocalCatalogBackfillOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
}
