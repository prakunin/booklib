package org.booklore.task.tasks;

import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.enums.TaskType;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpxExtractionCacheCleanupTaskTest {

    @Mock
    private ArchivedBookContentService archivedBookContentService;

    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private InpxExtractionCacheCleanupTask task;

    private BookLoreUser user;
    private TaskCreateRequest request;

    @BeforeEach
    void setUp() {
        user = BookLoreUser.builder()
                .permissions(new BookLoreUser.UserPermissions())
                .build();
        request = new TaskCreateRequest();
    }

    @Test
    void validatePermissions_shouldThrowException_whenUserCannotAccessTaskManager() {
        user.getPermissions().setCanAccessTaskManager(false);
        assertThrows(APIException.class, () -> task.validatePermissions(user, request));
    }

    @Test
    void validatePermissions_shouldPass_whenUserCanAccessTaskManager() {
        user.getPermissions().setCanAccessTaskManager(true);
        assertDoesNotThrow(() -> task.validatePermissions(user, request));
    }

    @Test
    void execute_shouldEvictUsingTheConfiguredLimitInBytes() {
        settingsWithLimit(10240);
        when(archivedBookContentService.evictBeyondCacheLimit(anyLong()))
                .thenReturn(new ArchivedBookContentService.EvictionResult(3, 12345L));

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskType.CLEANUP_INPX_EXTRACTION_CACHE, response.getTaskType());
        assertEquals(TaskStatus.COMPLETED, response.getStatus());
        verify(archivedBookContentService).evictBeyondCacheLimit(10240L * 1024 * 1024);
    }

    /** The multiplication has to happen in longs: 10 GB in bytes overflows an int. */
    @Test
    void execute_shouldNotOverflowOnLimitsBeyondTwoGigabytes() {
        settingsWithLimit(4096);
        when(archivedBookContentService.evictBeyondCacheLimit(anyLong()))
                .thenReturn(new ArchivedBookContentService.EvictionResult(0, 0L));

        task.execute(request);

        verify(archivedBookContentService).evictBeyondCacheLimit(4294967296L);
    }

    @Test
    void execute_shouldSkipTheSweep_whenNoLimitIsConfigured() {
        settingsWithLimit(null);

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskStatus.COMPLETED, response.getStatus());
        verify(archivedBookContentService, never()).evictBeyondCacheLimit(anyLong());
    }

    @Test
    void execute_shouldReturnFailed_whenTheSweepThrows() {
        settingsWithLimit(10240);
        when(archivedBookContentService.evictBeyondCacheLimit(anyLong()))
                .thenThrow(new RuntimeException("cache unreadable"));

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskStatus.FAILED, response.getStatus());
    }

    private void settingsWithLimit(Integer limitMb) {
        when(appSettingService.getAppSettings())
                .thenReturn(AppSettings.builder().inpxExtractionCacheSizeInMb(limitMb).build());
    }
}
