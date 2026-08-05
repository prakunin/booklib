package org.booklore.service.task;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.TaskInfo;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.CronConfig;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.task.tasks.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskServiceTest {

    private AuthenticationService authenticationService;
    private TaskHistoryService taskHistoryService;
    private TaskCronService taskCronService;
    private TaskCancellationManager cancellationManager;
    private Executor taskExecutor;
    private ObjectMapper objectMapper;
    private TaskScheduler taskScheduler;
    private TaskService taskService;
    private Task mockTask;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        taskHistoryService = mock(TaskHistoryService.class);
        taskCronService = mock(TaskCronService.class);
        cancellationManager = mock(TaskCancellationManager.class);
        taskExecutor = mock(Executor.class);
        objectMapper = mock(ObjectMapper.class);
        taskScheduler = mock(TaskScheduler.class);

        mockTask = mock(Task.class);
        when(mockTask.getTaskType()).thenReturn(TaskType.CLEANUP_TEMP_METADATA);

        taskService = new TaskService(
                authenticationService,
                taskHistoryService,
                taskCronService,
                List.of(mockTask),
                cancellationManager,
                taskExecutor,
                objectMapper,
                taskScheduler
        );
    }

    @Test
    void testRunAsUserThrowsExceptionForNullRequest() {
        APIException ex = assertThrows(APIException.class, () -> taskService.runAsUser(null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void testRunAsUserThrowsExceptionForNullTaskType() {
        TaskCreateRequest req = TaskCreateRequest.builder().triggeredByCron(false).build();
        APIException ex = assertThrows(APIException.class, () -> taskService.runAsUser(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @Disabled("guess: introduced already disabled with the initial task-scheduling implementation (#1427) and never re-enabled; no reason recorded in history, and TaskService/TaskType have since evolved — a maintainer should confirm whether it still fails before re-enabling")
    void testGetAvailableTasksReturnsNonNull() {
        CronConfig cronConfig = CronConfig.builder()
                .taskType(TaskType.CLEANUP_TEMP_METADATA)
                .enabled(false)
                .build();
        when(taskCronService.getCronConfigOrDefault(any())).thenReturn(cronConfig);
        List<TaskInfo> tasks = taskService.getAvailableTasks();
        assertNotNull(tasks);
        assertTrue(tasks.stream().anyMatch(t -> t.getTaskType() == TaskType.CLEANUP_TEMP_METADATA));
    }

    /**
     * Task Management renders a Run card for every task {@code getAvailableTasks} returns, and its
     * generic Run path sends {@code options: null} for everything except
     * {@code REFRESH_LIBRARY_METADATA}. {@code LOCAL_CATALOG_BACKFILL} requires a {@code libraryId} —
     * the catalog it reads is a per-library setting — so a card there is a button that can only ever
     * fail {@code requireLibraryId} with a generic "failed to start" toast, from a screen that has no
     * way to succeed. It is launched from the INPX archive panel instead, which knows the library.
     * <p>
     * {@code REFRESH_METADATA_MANUAL} is the existing precedent for exactly this shape and is hidden
     * for exactly this reason.
     */
    @Test
    void backfillIsNotOfferedAsAGenericRunnableTask() {
        when(taskCronService.getCronConfigOrDefault(any())).thenReturn(
                CronConfig.builder().taskType(TaskType.CLEANUP_TEMP_METADATA).enabled(false).build());

        List<TaskInfo> tasks = taskService.getAvailableTasks();

        assertTrue(tasks.stream().noneMatch(t -> t.getTaskType() == TaskType.LOCAL_CATALOG_BACKFILL),
                "LOCAL_CATALOG_BACKFILL must be hiddenFromUI: Task Management's generic Run button "
                        + "cannot supply the libraryId the task requires");
        assertTrue(tasks.stream().noneMatch(t -> t.getTaskType() == TaskType.REFRESH_METADATA_MANUAL),
                "REFRESH_METADATA_MANUAL is the precedent this follows and must stay hidden too");
    }

    @Test
    void testRunAsUserSyncTask() {
        BookLoreUser user = new BookLoreUser();
        user.setId(1L);
        user.setUsername("user1");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(mockTask.execute(any())).thenReturn(TaskCreateResponse.builder().taskType(TaskType.CLEANUP_TEMP_METADATA).build());
        TaskCreateRequest req = TaskCreateRequest.builder().taskType(TaskType.CLEANUP_TEMP_METADATA).triggeredByCron(false).build();
        TaskCreateResponse resp = taskService.runAsUser(req);
        assertEquals(TaskType.CLEANUP_TEMP_METADATA, resp.getTaskType());
    }

    @Test
    void testExecuteTaskThrowsForUnknownTaskType() {
        TaskCreateRequest req = TaskCreateRequest.builder().taskType(TaskType.CLEANUP_DELETED_BOOKS).triggeredByCron(false).build();
        BookLoreUser user = new BookLoreUser();
        user.setId(1L);
        user.setUsername("user1");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        assertThrows(UnsupportedOperationException.class, () -> taskService.runAsUser(req));
    }

    @Test
    void testParallelTaskAllowsMultipleRuns() {
        TaskType parallelType = TaskType.CLEANUP_TEMP_METADATA;
        when(mockTask.getTaskType()).thenReturn(parallelType);
        when(mockTask.execute(any())).thenReturn(TaskCreateResponse.builder().taskType(parallelType).build());
        BookLoreUser user = new BookLoreUser();
        user.setId(2L);
        user.setUsername("parallelUser");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        TaskCreateRequest req1 = TaskCreateRequest.builder().taskType(parallelType).triggeredByCron(false).build();
        TaskCreateRequest req2 = TaskCreateRequest.builder().taskType(parallelType).triggeredByCron(false).build();

        TaskCreateResponse resp1 = taskService.runAsUser(req1);
        TaskCreateResponse resp2 = taskService.runAsUser(req2);

        assertEquals(parallelType, resp1.getTaskType());
        assertEquals(parallelType, resp2.getTaskType());
    }

    @Test
    void testNonParallelTaskBlocksSecondRunAsUser() {
        TaskType nonParallelType = TaskType.REFRESH_LIBRARY_METADATA;
        Task nonParallelTask = mock(Task.class);
        when(nonParallelTask.getTaskType()).thenReturn(nonParallelType);
        when(nonParallelTask.execute(any())).thenReturn(TaskCreateResponse.builder().taskType(nonParallelType).build());

        taskService = new TaskService(
                authenticationService,
                taskHistoryService,
                taskCronService,
                List.of(nonParallelTask),
                cancellationManager,
                taskExecutor,
                objectMapper,
                taskScheduler
        );

        BookLoreUser user = new BookLoreUser();
        user.setId(3L);
        user.setUsername("nonParallelUser");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        TaskCreateRequest req = TaskCreateRequest.builder().taskType(nonParallelType).triggeredByCron(false).build();
        taskService.runAsUser(req);

        APIException ex = assertThrows(APIException.class, () -> taskService.runAsUser(req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void testCancelNonExistentTaskThrowsException() {
        BookLoreUser user = new BookLoreUser();
        user.setId(4L);
        user.setUsername("cancelUser");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        String fakeTaskId = "not-running-task-id";
        assertThrows(APIException.class, () -> taskService.cancelTask(fakeTaskId));
    }

    @Test
    void testAsyncTaskReturnsAcceptedStatus() {
        TaskType asyncType = TaskType.UPDATE_BOOK_RECOMMENDATIONS;
        Task asyncTask = mock(Task.class);
        when(asyncTask.getTaskType()).thenReturn(asyncType);
        when(asyncTask.execute(any())).thenReturn(TaskCreateResponse.builder().taskType(asyncType).build());

        taskService = new TaskService(
                authenticationService,
                taskHistoryService,
                taskCronService,
                List.of(asyncTask),
                cancellationManager,
                taskExecutor,
                objectMapper,
                taskScheduler
        );

        BookLoreUser user = new BookLoreUser();
        user.setId(5L);
        user.setUsername("asyncUser");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        TaskCreateRequest req = TaskCreateRequest.builder().taskType(asyncType).triggeredByCron(false).build();
        TaskCreateResponse resp = taskService.runAsUser(req);

        assertEquals(asyncType, resp.getTaskType());
        assertEquals(TaskStatus.ACCEPTED, resp.getStatus());
    }

    @Test
    void taskOverviewIncludesQueuedAsyncTask() {
        TaskType asyncType = TaskType.UPDATE_BOOK_RECOMMENDATIONS;
        Task asyncTask = mock(Task.class);
        when(asyncTask.getTaskType()).thenReturn(asyncType);

        taskService = new TaskService(
                authenticationService,
                taskHistoryService,
                taskCronService,
                List.of(asyncTask),
                cancellationManager,
                taskExecutor,
                objectMapper,
                taskScheduler
        );

        BookLoreUser user = new BookLoreUser();
        user.setId(10L);
        user.setUsername("admin");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        TaskCreateResponse response = taskService.runAsUser(
                TaskCreateRequest.builder().taskType(asyncType).build());
        var overview = taskService.getTaskOverview();

        assertEquals(1, overview.activeTasks().size());
        assertEquals(response.getTaskId(), overview.activeTasks().getFirst().taskId());
        assertEquals(asyncType, overview.activeTasks().getFirst().taskType());
    }

    @Test
    void taskOverviewIncludesEnabledRuntimeSchedule() {
        CronConfig cronConfig = CronConfig.builder()
                .taskType(TaskType.CLEANUP_TEMP_METADATA)
                .enabled(true)
                .cronExpression("0 0 * * * *")
                .build();
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        when(taskCronService.getCronConfigOrDefault(TaskType.CLEANUP_TEMP_METADATA)).thenReturn(cronConfig);
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        when(scheduledFuture.getDelay(TimeUnit.MILLISECONDS)).thenReturn(60_000L);

        taskService.rescheduleTask(TaskType.CLEANUP_TEMP_METADATA);
        var overview = taskService.getTaskOverview();

        assertEquals(1, overview.scheduledTasks().size());
        assertEquals(TaskType.CLEANUP_TEMP_METADATA, overview.scheduledTasks().getFirst().taskType());
        assertEquals("0 0 * * * *", overview.scheduledTasks().getFirst().cronExpression());
        assertNotNull(overview.scheduledTasks().getFirst().nextRunAt());
    }

    @Test
    void testNullOptionsHandledGracefully() {
        TaskType type = TaskType.CLEANUP_TEMP_METADATA;
        when(mockTask.getTaskType()).thenReturn(type);
        when(mockTask.execute(any())).thenReturn(TaskCreateResponse.builder().taskType(type).build());
        BookLoreUser user = new BookLoreUser();
        user.setId(6L);
        user.setUsername("nullOptionsUser");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        TaskCreateRequest req = TaskCreateRequest.builder().taskType(type).options(null).triggeredByCron(false).build();
        TaskCreateResponse resp = taskService.runAsUser(req);
        assertEquals(type, resp.getTaskType());
    }

    @Test
    void testExceptionInTaskExecutionPropagates() {
        TaskType type = TaskType.CLEANUP_TEMP_METADATA;
        when(mockTask.getTaskType()).thenReturn(type);
        when(mockTask.execute(any())).thenThrow(new RuntimeException("Task failed"));
        BookLoreUser user = new BookLoreUser();
        user.setId(7L);
        user.setUsername("exceptionUser");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        TaskCreateRequest req = TaskCreateRequest.builder().taskType(type).triggeredByCron(false).build();
        assertThrows(RuntimeException.class, () -> taskService.runAsUser(req));
    }

    @Test
    void testRunAsUserThrowsExceptionForNullUser() {
        when(authenticationService.getAuthenticatedUser()).thenReturn(null);
        TaskCreateRequest req = TaskCreateRequest.builder().taskType(TaskType.CLEANUP_TEMP_METADATA).triggeredByCron(false).build();
        assertThrows(NullPointerException.class, () -> taskService.runAsUser(req));
    }

    @Test
    void testExecuteTaskThrowsForMissingTaskInRegistry() {
        TaskCreateRequest req = TaskCreateRequest.builder().taskType(TaskType.CLEANUP_DELETED_BOOKS).triggeredByCron(false).build();
        BookLoreUser user = new BookLoreUser();
        user.setId(8L);
        user.setUsername("missingTaskUser");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        assertThrows(UnsupportedOperationException.class, () -> taskService.runAsUser(req));
    }

    @Test
    void testConvertOptionsToMapHandlesInvalidType() {
        BookLoreUser user = new BookLoreUser();
        user.setId(9L);
        user.setUsername("invalidOptionsUser");
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenThrow(new IllegalArgumentException("Conversion failed"));
        TaskCreateRequest req = TaskCreateRequest.builder().taskType(TaskType.CLEANUP_TEMP_METADATA).options(new Object()).triggeredByCron(false).build();
        when(mockTask.execute(any())).thenReturn(TaskCreateResponse.builder().taskType(TaskType.CLEANUP_TEMP_METADATA).build());
        TaskCreateResponse resp = taskService.runAsUser(req);
        assertEquals(TaskType.CLEANUP_TEMP_METADATA, resp.getTaskType());
    }

    @Test
    void testInitializeScheduledTasksDoesNotThrow() {
        when(taskCronService.getAllEnabledCronConfigs()).thenReturn(List.of());
        assertDoesNotThrow(() -> taskService.initializeScheduledTasks());
    }

    @Test
    void testRescheduleTaskDoesNotThrowWhenEnabled() {
        CronConfig cronConfig = CronConfig.builder()
                .taskType(TaskType.CLEANUP_TEMP_METADATA)
                .enabled(true)
                .cronExpression("0 0 0 1 1 0")
                .build();
        when(taskCronService.getCronConfigOrDefault(TaskType.CLEANUP_TEMP_METADATA)).thenReturn(cronConfig);
        assertDoesNotThrow(() -> taskService.rescheduleTask(TaskType.CLEANUP_TEMP_METADATA));
    }
}
