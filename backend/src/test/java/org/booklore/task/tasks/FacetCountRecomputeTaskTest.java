package org.booklore.task.tasks;

import org.booklore.app.service.AppBookService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FacetCountRecomputeTaskTest {

    @Mock
    private AppBookService appBookService;

    @InjectMocks
    private FacetCountRecomputeTask task;

    private final TaskCreateRequest request = TaskCreateRequest.builder().build();

    @Test
    void recomputesEveryDirtyLibraryAndCompletes() {
        when(appBookService.findDirtyLibraryIds()).thenReturn(List.of(1L, 2L, 3L));

        TaskCreateResponse response = task.execute(request);

        verify(appBookService).recomputeLibraryFacetCounts(1L);
        verify(appBookService).recomputeLibraryFacetCounts(2L);
        verify(appBookService).recomputeLibraryFacetCounts(3L);
        assertThat(response.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(response.getTaskType()).isEqualTo(TaskType.RECOMPUTE_FACET_COUNTS);
        assertThat(response.getTaskId()).isNotBlank();
    }

    @Test
    void oneLibraryFailureDoesNotAbortTheSweep() {
        when(appBookService.findDirtyLibraryIds()).thenReturn(List.of(1L, 2L));
        doThrow(new RuntimeException("boom")).when(appBookService).recomputeLibraryFacetCounts(1L);

        TaskCreateResponse response = task.execute(request);

        // The second library is still recomputed and the task completes despite the first failure.
        verify(appBookService).recomputeLibraryFacetCounts(2L);
        assertThat(response.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void dirtySweepFailureMarksTaskFailed() {
        when(appBookService.findDirtyLibraryIds()).thenThrow(new RuntimeException("db down"));

        TaskCreateResponse response = task.execute(request);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void getTaskTypeIsRecomputeFacetCounts() {
        assertThat(task.getTaskType()).isEqualTo(TaskType.RECOMPUTE_FACET_COUNTS);
    }

    @Test
    void validatePermissionsRejectsUsersWithoutTaskManagerAccess() {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        BookLoreUser user = BookLoreUser.builder().id(1L).permissions(permissions).build();

        assertThatThrownBy(() -> task.validatePermissions(user, request))
                .isInstanceOf(APIException.class);
    }

    @Test
    void validatePermissionsAllowsUsersWithTaskManagerAccess() {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setCanAccessTaskManager(true);
        BookLoreUser user = BookLoreUser.builder().id(1L).permissions(permissions).build();

        assertThatCode(() -> task.validatePermissions(user, request)).doesNotThrowAnyException();
    }
}
